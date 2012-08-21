package nats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session represents a bidirectional channel to NATS server. Event handler may be attached to each operation
 * which is invoked when the operation is processed by the server. A client JVM may create multiple Session objects
 * by calling {@link #connect(java.util.Properties popts)} multiple times.
 * 
 * @author Teppei Yagihashi
 *
 */

public final class Session {

	public static final String version = "0.3";
	
	public static final int DEFAULT_PORT = 4222;
	public static final String DEFAULT_URI = "nats://localhost:" + Integer.toString(DEFAULT_PORT);

	public static final int MAX_CONTROL_LINE_SIZE = 512;

	// Parser state
	public static final int AWAITING_CONTROL = 0;
	public static final int AWAITING_MSG_PAYLOAD = 1;

	// Reconnect Parameters, 2 sec wait, 10 tries
	public static final int DEFAULT_RECONNECT_TIME_WAIT = 2*1000;
	public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;

	public static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024;
    
	public static final String CR_LF = "\r\n";
	public static final int CR_LF_LEN = CR_LF.length();
	public static final String EMPTY = "";
	public static final String SPC = " ";

	// Protocol
	public static final byte[] PUB = "PUB".getBytes();
	public static final byte[] SUB = "SUB".getBytes();
	public static final byte[] UNSUB = "UNSUB".getBytes();
	public static final byte[] CONNECT = "CONNECT".getBytes();
	public static final byte[] MSG = "MSG".getBytes();
	public static final byte[] PONG = "PONG".getBytes();
	public static final byte[] PING = "PING".getBytes();
	public static final byte[] INFO = "INFO".getBytes();
	public static final byte[] ERR = "-ERR".getBytes();
	public static final byte[] OK = "+OK".getBytes();

	// Responses
	public static final byte[] PING_REQUEST = ("PING" + CR_LF).getBytes();
	public static final int PING_REQUEST_LEN = PING_REQUEST.length;
	public static final byte[] PONG_RESPONSE = ("PONG" + CR_LF).getBytes();
	public static final int PONG_RESPONSE_LEN = PONG_RESPONSE.length;

	private static int numSessions;
	private static volatile int ssid;
	private Session self;
	private Session.EventHandler connectHandler;
	private Selector selector;
	private Session.SelectorThread selectorThread;

	private Properties opts;
	private InetSocketAddress addr;
	private SocketChannel channel;
	private SelectionKey sKey;
	private ByteBuffer sendBuffer;
	private ByteBuffer receiveBuffer;
	private int status;
	private ConcurrentHashMap<Integer, Subscription> subs;    
	private LinkedList<EventHandler> pongs;
	private Timer timer;

	private int msgs_sent;
	private int bytes_sent;
	private int msgs_received;
	private int bytes_received;

	static {
		ssid = 1;
		numSessions = 0;
	}

	/** 
	 * Create and return a Session with various attributes. 
	 * @param popts Properties object containing connection attributes
	 * @return newly created Session object
	 */
	public static Session connect(Properties popts) throws IOException, InterruptedException {
		// Defaults
		if (!popts.contains("verbose")) popts.put("verbose", new Boolean(false));
		if (!popts.contains("pedantic")) popts.put("pedantic", new Boolean(false));
		if (!popts.contains("reconnect")) popts.put("reconnect", new Boolean(false));
		if (!popts.contains("ssl")) popts.put("ssl", new Boolean(false));
		if (!popts.contains("max_reconnect_attempts")) popts.put("max_reconnect_attempts", new Integer(DEFAULT_MAX_RECONNECT_ATTEMPTS));
		if (!popts.contains("reconnect_time_wait")) popts.put("reconnect_time_wait", new Integer(DEFAULT_RECONNECT_TIME_WAIT));

		// Overriding with ENV
		if (System.getenv("NATS_URI") != null) popts.put("uri", System.getenv("NATS_URI")); else if (!popts.contains("uri")) popts.put("uri", DEFAULT_URI);
		if (System.getenv("NATS_VERBOSE") != null) popts.put("verbose", new Boolean(System.getenv("NATS_VERBOSE")));
		if (System.getenv("NATS_PEDANTIC") != null) popts.put("pedantic", new Boolean(System.getenv("NATS_PEDANTIC")));
		if (System.getenv("NATS_DEBUG") != null) popts.put("debug", new Boolean(System.getenv("NATS_DEBUG")));
		if (System.getenv("NATS_RECONNECT") != null) popts.put("reconnect", new Boolean(System.getenv("NATS_RECONNECT")));
		if (System.getenv("NATS_FAST_PRODUCER") != null) popts.put("fast_producer", new Boolean(System.getenv("NATS_FAST_PRODUCER")));
		if (System.getenv("NATS_SSL") != null) popts.put("ssl", new Boolean(System.getenv("NATS_SSL")));
		if (System.getenv("NATS_MAX_RECONNECT_ATTEMPTS") != null) popts.put("max_reconnect_attempts", Integer.parseInt(System.getenv("NATS_MAX_RECONNECT_ATTEMPTS")));
		if (System.getenv("NATS_MAX_RECONNECT_TIME_WAIT") != null) popts.put("max_reconnect_time_wait", Integer.parseInt(System.getenv("NATS_MAX_RECONNECT_TIME_WAIT")));

		return new Session(popts);
	}
	
	private Session(Properties popts) throws IOException, InterruptedException {
		self = this;
		selector = SelectorProvider.provider().openSelector();
		selectorThread = new SelectorThread();
		sendBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE);
		receiveBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE);
		status = AWAITING_CONTROL;
		msgs_sent = bytes_sent = 0;
		subs = new ConcurrentHashMap<Integer, Subscription>();
		pongs = new LinkedList<EventHandler>();

		opts = popts;
		String[] uri = ((String)opts.get("uri")).split(":");
		addr = new InetSocketAddress(uri[1].substring(2, uri[1].length()), Integer.parseInt(uri[2]));
		connect();

		timer = new Timer("NATS_Timer-" + numSessions);
	}

	private boolean connect() throws IOException {
		try {
			channel = SocketChannel.open();
			channel.connect(addr);
			channel.configureBlocking(false);
			sKey = channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
		} catch(IOException ie) {
			ie.printStackTrace();
			return false;
		}

		return true;
	}

	private String hexRand(int limit, Random rand) {
		return Integer.toHexString(rand.nextInt(limit));
	}

	/**
	 * Create a properly formatted inbox subject.
	 * @return String value representing inbox subject
	 */
	public String createInbox()
	{
		Random rand = new Random();
		return "_INBOX." + hexRand(0x0010000, rand) 
				+ hexRand(0x0010000, rand)
				+ hexRand(0x0010000, rand)
				+ hexRand(0x0010000, rand)
				+ hexRand(0x0010000, rand)
				+ hexRand(0x0100000, rand);
	}

	/**
	 * Establish a connection to the server and start a background thread for processing incoming and outgoing messages
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void start() throws IOException, InterruptedException {
		start(null);
	}

	/**
	 * Establish a connection to the server and start a background thread for processing incoming and outgoing messages
	 * @param handler EventHanlder invoked when connection is established.
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void start(EventHandler handler) throws IOException, InterruptedException {
		if (handler != null) {
			connectHandler = handler;
			connectHandler.verifyArity();
		}
		selectorThread.start();    	
		
		this.sendCommand("CONNECT {\"verbose\":" + ((Boolean)opts.get("verbose")).toString() + ",\"pedantic\":" + ((Boolean)opts.get("pedantic")) + "}" + CR_LF);
		numSessions++;
	}

	/**
	 * Close the channel and stopping the background thread.
	 * @throws IOException
	 */
	public void stop() throws IOException {
		channel.close();
		numSessions--;
		selector.close();
		selectorThread.interrupt();
		timer.purge();
	}

	/**
	 * Return status (true or false) of a connection to the server.
	 * @return connection status
	 */
	public boolean isConnected() {
		return channel.isConnected();
	}

	/**
	 * Publish a message to the given subject.
	 * @param subject
	 * @param msg a message to be delivered to the server
	 * @throws IOException
	 */
	public void publish(String subject, String msg) throws IOException {
		publish(subject, null, msg, null);
	}

	/**
	 * Publish a message to the given subject.
	 * @param subject
	 * @param msg a message to be delivered to the server
	 * @param handler event handler is invoked when publish has been processed by the server.
	 * @throws IOException
	 */
	public void publish(String subject, String msg, EventHandler handler) throws IOException {
		publish(subject, null, msg, handler);
	}

	/**
	 * Publish a message to the given subject, with optional reply and event handler.
	 * @param subject
	 * @param opt_reply
	 * @param msg a message to be delivered to the server
	 * @param handler event handler is invoked when publish has been processed by the server.
	 * @throws IOException
	 */
	byte[] cmd = new byte[MAX_BUFFER_SIZE];
	public void publish(String subject, String opt_reply, String msg, EventHandler handler) throws IOException {
		if (subject == null) return;

		int offset = bytesCopy(cmd, 0, "PUB ");
		offset = bytesCopy(cmd, offset, subject);
		cmd[offset++] = 0x20; // SPC
		if (opt_reply != null)  {
			offset = bytesCopy(cmd, offset, opt_reply);
			cmd[offset++] = 0x20;
		}
		offset = bytesCopy(cmd, offset, Integer.toString(msg.length()));
		cmd[offset++] = 0xd; // CRLF
		cmd[offset++] = 0xa;
		offset = bytesCopy(cmd, offset, msg);
		cmd[offset++] = 0xd; // CRLF
		cmd[offset++] = 0xa;

		sendCommand(cmd, offset);

		if (msg != null) {
			msgs_sent++;
			bytes_sent += msg.length();
		}
		
		if (handler != null) sendPing(handler);
	}

	private int bytesCopy(byte[] b, int start, String data) {
		int end = start + data.length();
		for(int idx = 0; start < end; start++, idx++)
			b[start] = (byte)data.charAt(idx);

		return end;
	}

	/**
	 * Subscribe to a subject with optional wildcards. Messages will be delivered to the supplied callback.
	 * @param subject optionally with wildcards
	 * @param handler event handler is invoked when a message is delivered
	 * @return subscription ID
	 * @throws IOException
	 */
	public Integer subscribe(String subject, EventHandler handler) throws IOException {
		return this.subscribe(subject, null, handler);
	}

	/**
	 * Subscribe to a subject with optional wildcards. Messages will be delivered to the supplied callback.
	 * @param subject optionally with wildcards
	 * @param popts optional option properties, e.g. queue, max
	 * @param handler event handler is invoked when a message is delivered
	 * @return subscription ID
	 * @throws IOException
	 */
	public Integer subscribe(String subject, Properties popts, EventHandler handler) throws IOException {
		Integer sid = ssid++;
		Subscription sub = new Subscription(sid, subject, handler);

		if (popts != null) {
			sub.queue = (popts.getProperty("queue") == null ? " " : (String)popts.getProperty("queue"));
			sub.max = (popts.getProperty("max") == null ? -1 : Integer.parseInt(popts.getProperty("max")));
		}

		subs.put(sid, sub);
		sub.handler.verifyArity();
		sendSubscription(subject, sid, sub);

		return sid;
	}

	private void sendSubscription(String subject, Integer sid, Subscription sub) throws IOException {
		sendCommand("SUB " + subject + SPC + sub.queue + SPC + sid.toString() + CR_LF);

		if (sub.max != -1) this.unsubscribe(sid, sub.max);
	}

	private void sendSubscirptions() throws IOException {
		Entry<Integer, Subscription> entry = null;
    	
		for(Iterator<Entry<Integer, Session.Subscription>> iter = subs.entrySet().iterator(); iter.hasNext();) {
			entry = iter.next();
			sendSubscription(entry.getValue().subject, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Cancel a subscription.
	 * @param sid
	 * @throws IOException
	 */
	public void unsubscribe(Integer sid) throws IOException {
		this.unsubscribe(sid, 0);
	}

	/**
	 * Cancel a subscription.
	 * @param sid 
	 * @param opt_max optional number of responses to receive before auto-unsubscribing
	 * @throws IOException
	 */
	public void unsubscribe(Integer sid, int opt_max) throws IOException {
		Subscription sub = subs.get(sid);
		if (sub == null) return;
		if (opt_max < 0) opt_max = 0;
    	    	
		sendCommand("UNSUB " + sid.toString() + SPC + Integer.toString(opt_max) + CR_LF);
    	
		if (sub.received >= opt_max) subs.remove(sid);
	}
    
	/**
	 * Return the active subscription count.
	 * @return subscription count
	 */
	public int getSubscriptionCount() {
		return subs.size();
	}
    
	private void sendCommand(String command) throws IOException {
		sendCommand(command.getBytes(), command.length());
	}
    
	private void sendCommand(byte[] data, int length) throws IOException {
		for(;;) { 
			try {
				synchronized(sendBuffer) {
					sendBuffer.put(data, 0, length);
					if (sKey.interestOps() == 1) channel.register(selector, 0x5); // read & write mode
				}
				break;
			} catch(BufferOverflowException bof) {}
		}
	}
    
	private void sendPing(EventHandler handler) throws IOException {
		handler.verifyArity();
		synchronized(pongs) {
			pongs.add(handler);
		}
		synchronized(sendBuffer) {
			sendBuffer.put(PING_REQUEST, 0, PING_REQUEST_LEN);
			if (sKey.interestOps() == 1) channel.register(selector, 0x5); // read & write mode
		}
	}
    
	/**
	 * Send a request and have the response delivered to the supplied handler.
	 * @param subject
	 * @param handler
	 * @return subscription ID
	 * @throws IOException
	 */
	public Integer request(String subject, EventHandler handler) throws IOException {
		return request(subject, EMPTY, null, handler);
	}

	/**
	 * Send a request and have the response delivered to the supplied handler.
	 * @param subject
	 * @param data
	 * @param popts
	 * @param handler
	 * @return subscription ID
	 * @throws IOException
	 */
	public Integer request(String subject, String data, Properties popts, EventHandler handler) throws IOException {
		if (subject == null) return null;
    	
		String inbox = createInbox();
		Integer sub = subscribe(inbox, popts, handler);
		publish(subject, inbox, data, null);
    	
		return sub;
	}
    
	/**
	 * Flush buffered messages with no event handler.
	 */
	public void flush() throws IOException {
		flush(emptyHandler);
	}

	/**
	 * Flush buffered messages with a specified event handler.
	 * @param event handler is invoked when flush is completed.
	 */
	public void flush(EventHandler handler) throws IOException {
		handler.caller = Thread.currentThread();
		sendPing(handler);
		try {
			handler.caller.join();
		} catch (InterruptedException e) {
		}
	}
    
	/**
	 * Return version String.
	 * @return version String
	 */
	public String inspect() {
		return "<nats java " + version + ">";
	}
    
	private void reconnect() throws IOException {
		int max_reconnect_attempts = ((Integer)opts.get("max_reconnect_attempts")).intValue();
		int reconnect_time_wait = ((Integer)opts.get("reconnect_time_wait")).intValue();
		
		for(int i = 0; i < max_reconnect_attempts; i++) {
			channel.close();
			connect();
			if (isConnected()) {
				sendSubscirptions();
				break;
			}
			
			try {
				Thread.sleep(reconnect_time_wait);
			} catch(InterruptedException ie) {}
		}
	}

	/**
	 * Set a timeout on a subscription.
	 * @param sid subscription ID
	 * @param tout
	 * @param prop
	 * @param handler
	 */
	public void timeout(final Integer sid, long tout, Properties prop, final EventHandler handler) {
		Subscription sub = subs.get(sid);
		if (sub == null) return;
		
		handler.verifyArity();
		
		boolean au = false;
		if (prop != null) {
			au = (prop.get("auto_unsubscribe") == null ? false : ((Boolean)prop.get("auto_unsubscribe")).booleanValue());
			sub.expected = (prop.get("expected") == null ? -1 : ((Integer)prop.get("expected")).intValue());
		}
		final boolean auto_unsubscribe = au;
		
		if (sub.task != null) sub.task.cancel();
		
		final Session parent = this;
		TimerTask task = new TimerTask() {
			public void run() {
				try {
					if (auto_unsubscribe) parent.unsubscribe(sid);
				} catch(IOException e) {
					e.printStackTrace();
				}
				if (handler != null) handler.execute(sid);
			}
		}; 
		timer.schedule(task, tout * 1000);
		sub.task = task;
	}
	

	/**
	 * Event handler can be passed to various operations and invoked when the operation is processed by the server.
	 * @author Teppei Yagihashi
	 */
	private static final Class<?>[] ARITY0 = {};
	private static final Class<?>[] ARITY1 = {String.class};
	private static final Class<?>[] ARITY2 = {String.class, String.class};
	private static final Class<?>[] ARITY3 = {String.class, String.class, String.class};
	private static final Class<?>[] OBJ = {Object.class};
	public abstract class EventHandler {
		private static final String className = "nats.Session$EventHandler";
		private Thread caller;
		private int arity;
		
		public void execute() {}
		public void execute(String msg) {}
		public void execute(String msg, String reply) {}
		public void execute(String msg, String reply, String subject) {}		
		public void execute(Object o) {}
		
		private void verifyArity() {
			try {
				if (!getClass().getMethod("execute", ARITY0).getDeclaringClass().getName().equals(className))
					arity = 0;
				else if (!getClass().getMethod("execute", ARITY1).getDeclaringClass().getName().equals(className))
					arity = 1;
				else if (!getClass().getMethod("execute", ARITY2).getDeclaringClass().getName().equals(className))
					arity = 2;
				else if (!getClass().getMethod("execute", ARITY3).getDeclaringClass().getName().equals(className))
					arity = 3;
				else if (!getClass().getMethod("execute", OBJ).getDeclaringClass().getName().equals(className))
					arity = -1;
			} catch (Exception e) {
				e.printStackTrace();
			}			
		}
	}

	// Dummy event handler
	private EventHandler emptyHandler = new EventHandler() {};
    
	/**
	 * Internal data structure to hold subscription meta data
	 * @author Teppei Yagihashi
	 */
	private class Subscription {
		public Integer sid = null;
		public String subject = null;
		public EventHandler handler = null;
		public String queue = "";
		public int max = -1;
		public int received = 0;
		public int expected = -1;
		public TimerTask task = null;
    	
		public Subscription(Integer psid, String psubject, EventHandler phandler) {
			sid = psid;
			subject = psubject;
			handler = phandler;
		}
	}
    
	/**
	 * Main thread for processing incoming and outgoing messages through NIO channel
	 */
	private final class SelectorThread extends Thread {
		private byte[] buf = new byte[MAX_BUFFER_SIZE];
		private int pos;
		private String subject;
		private String optReply;
		private int payload_length;
		private Subscription sub;

		public SelectorThread() {
			for(int l = 0; l < MAX_BUFFER_SIZE; l++)
				buf[l] = '\0';		
			pos = 0;
			payload_length = -1;
		}
		
		public void run() {
			try {
				for(;;) {
					selector.select(1);
					if (sKey.isWritable()) {
						synchronized(sendBuffer) {							
							sendBuffer.flip();
							for(;;) {
								if (sendBuffer.position() < sendBuffer.limit())	
									channel.write(sendBuffer);
								else break;
							}
							sendBuffer.clear();
							sKey.interestOps(0x1); // read only mode
						}
					}
					else if (sKey.isReadable()) processMessage();
				}
			} catch(Exception e) {}	
		}    	
		
		/**
		 * Parse and process various incoming messages
		 */
		private void processMessage() throws IOException {
			if (channel.read(receiveBuffer) > 0) {
				int diff = 0;
				receiveBuffer.flip();
				for(;;) {
					if (receiveBuffer.position() >= receiveBuffer.limit())
						break;
					switch(status) {
					case AWAITING_CONTROL :
						if ((pos = readNextOp(receiveBuffer)) == 0) {
							if (comp(buf, MSG, 3)) {
								status = AWAITING_MSG_PAYLOAD;
								parseMsg();
								if (receiveBuffer.limit() < (payload_length + receiveBuffer.position() + 2)) {
								    diff = receiveBuffer.limit() - receiveBuffer.position();
								    // Don't clear() yet and keep it in buffer
									receiveBuffer.compact();
									receiveBuffer.position(diff);
									return;									
								}
								break;
							}
							else if (comp(buf, PONG, 4)) {
								EventHandler handler;
								synchronized(pongs) {
									handler = pongs.poll();
								}
								processEvent(null, handler);
								if (handler.caller != null)
									handler.caller.interrupt();
							}
							else if (comp(buf, PING, 4)) sendCommand(PONG_RESPONSE, PONG_RESPONSE_LEN);
							else if (comp(buf, ERR, 4)) reconnect();
							else if (comp(buf, OK, 3)) {/* do nothing for now */}
							else if (comp(buf, INFO, 4)) {
								if (connectHandler != null) 
									connectHandler.execute((Object)self);
							}
						}
						break;
					case AWAITING_MSG_PAYLOAD :
						receiveBuffer.get(buf, 0, payload_length + 2);
						on_msg(new String(buf, 0, payload_length)); 
						pos = 0;
						status = AWAITING_CONTROL;					
						break;
					}
				}
				receiveBuffer.clear();
			}
		}
		
		private void on_msg(String msg) {
			msgs_received++;
			if (msg != null) bytes_received+=msg.length();

			sub.received++;
			if (sub.max != -1) {
				if (sub.max < sub.received)
					return;
				else if (sub.max == sub.received)
					subs.remove(sub.sid);
			}
			processEvent(msg, sub.handler);
			
			if ((sub.task != null) && (sub.received >= sub.expected)) {
				sub.task.cancel();
				sub.task = null;
			}
		}
		
		private void processEvent(String msg, EventHandler handler) {
			switch(handler.arity) {
			case 0 :
				handler.execute();
				break;
			case 1 :
				handler.execute(msg);
				break;
			case 2 :
				handler.execute(msg, optReply);
				break;
			case 3 :
				handler.execute(msg, optReply, subject);
				break;
			case -1 :
				handler.execute((Object)msg);
				break;
			}
		}
		
		private int readNextOp(ByteBuffer buffer) {
			int i = pos, limit = buffer.limit();
			
			for(; buffer.position() < limit; i++) {
				buf[i] = buffer.get();
				if ((i > 0) && (buf[i] == '\n') && (buf[i-1] == '\r')) 
					return 0;
			}
			return i;
		}
		
		private boolean comp(byte[] src, byte[] dest, int length) {
			for(int i = 0; i < length; i++)
				if (src[i] != dest[i])
					return false;
			return true;
		}

		// Extracting MSG parameters
		private void parseMsg() {
			int index = 0, rid = 0, start = 0;
			for( ; buf[index++] != 0xd; ) {
				if (buf[index] == 0x20) {
					if (rid == 1)
						subject = new String(new String(buf,start,index-start));						
					else if (rid == 2) 
						sub = subs.get(Integer.valueOf(new String(buf,start,index-start)));
					else if (rid == 3)
						optReply = new String(new String(buf,start,index-start));
					rid++;
					start = ++index;
				}
			}
			payload_length = Integer.parseInt(new String(buf,start,--index-start));
		}		
	}    
}