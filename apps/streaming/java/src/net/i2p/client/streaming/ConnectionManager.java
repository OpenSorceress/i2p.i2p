package net.i2p.client.streaming;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Coordinate all of the connections for a single local destination.
 *
 *
 */
class ConnectionManager {
    private final I2PAppContext _context;
    private final Log _log;
    private final I2PSession _session;
    private final MessageHandler _messageHandler;
    private final PacketHandler _packetHandler;
    private final ConnectionHandler _connectionHandler;
    private final PacketQueue _outboundQueue;
    private final SchedulerChooser _schedulerChooser;
    private final ConnectionPacketHandler _conPacketHandler;
    private final TCBShare _tcbShare;
    /** Inbound stream ID (Long) to Connection map */
    private final ConcurrentHashMap<Long, Connection> _connectionByInboundId;
    /** Ping ID (Long) to PingRequest */
    private final Map<Long, PingRequest> _pendingPings;
    private volatile boolean _throttlersInitialized;
    private final ConnectionOptions _defaultOptions;
    private final AtomicInteger _numWaiting = new AtomicInteger();
    private long _soTimeout;
    private volatile ConnThrottler _minuteThrottler;
    private volatile ConnThrottler _hourThrottler;
    private volatile ConnThrottler _dayThrottler;
    /** since 0.9, each manager instantiates its own timer */
    private final SimpleTimer2 _timer;
    /** cache of the property to detect changes */
    private static volatile String _currentBlacklist = "";
    private static final Set<Hash> _globalBlacklist = new ConcurrentHashSet<Hash>();
    
    /** @since 0.9.3 */
    public static final String PROP_BLACKLIST = "i2p.streaming.blacklist";

    /**
     *  Manage all conns for this session
     */
    public ConnectionManager(I2PAppContext context, I2PSession session, ConnectionOptions defaultOptions) {
        _context = context;
        _session = session;
        _defaultOptions = defaultOptions;
        _log = _context.logManager().getLog(ConnectionManager.class);
        _connectionByInboundId = new ConcurrentHashMap<Long,Connection>(32);
        _pendingPings = new ConcurrentHashMap<Long,PingRequest>(4);
        _messageHandler = new MessageHandler(_context, this);
        _packetHandler = new PacketHandler(_context, this);
        _connectionHandler = new ConnectionHandler(_context, this);
        _schedulerChooser = new SchedulerChooser(_context);
        _conPacketHandler = new ConnectionPacketHandler(_context);
        _timer = new RetransmissionTimer(_context, "Streaming Timer " +
                                         session.getMyDestination().calculateHash().toBase64().substring(0, 4));
        _tcbShare = new TCBShare(_context, _timer);
        // PROTO_ANY is for backward compatibility (pre-0.7.1)
        // TODO change proto to PROTO_STREAMING someday.
        // Right now we get everything, and rely on Datagram to specify PROTO_UDP.
        // PacketQueue has sent PROTO_STREAMING since the beginning of mux support (0.7.1)
        // As of 0.9.1, new option to enforce streaming protocol, off by default
        // As of 0.9.1, listen on configured port (default 0 = all)
        int protocol = defaultOptions.getEnforceProtocol() ? I2PSession.PROTO_STREAMING : I2PSession.PROTO_ANY;
        _session.addMuxedSessionListener(_messageHandler, protocol, defaultOptions.getLocalPort());
        _outboundQueue = new PacketQueue(_context, _session, this);
        /** Socket timeout for accept() */
        _soTimeout = -1;

        // Stats for this class
        _context.statManager().createRateStat("stream.con.lifetimeMessagesSent", "How many messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeMessagesReceived", "How many messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesSent", "How many bytes do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeBytesReceived", "How many bytes do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesSent", "How many duplicate messages do we send on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeDupMessagesReceived", "How many duplicate messages do we receive on a stream?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeRTT", "What is the final RTT when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeCongestionSeenAt", "When was the last congestion seen at when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.con.lifetimeSendWindowSize", "What is the final send window size when a stream closes?", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        _context.statManager().createRateStat("stream.receiveActive", "How many streams are active when a new one is received (period being not yet dropped)", "Stream", new long[] { 60*60*1000, 24*60*60*1000 });
        // Stats for Connection
        _context.statManager().createRateStat("stream.con.windowSizeAtCongestion", "How large was our send window when we send a dup?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeBegin", "How many messages were outstanding when we started to choke?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.chokeSizeEnd", "How many messages were outstanding when we stopped being choked?", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.fastRetransmit", "How long a packet has been around for if it has been resent per the fast retransmit timer?", "Stream", new long[] { 60*1000, 10*60*1000 });
        // Stats for PacketQueue
        _context.statManager().createRateStat("stream.con.sendMessageSize", "Size of a message sent on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("stream.con.sendDuplicateSize", "Size of a message resent on a connection", "Stream", new long[] { 60*1000, 10*60*1000, 60*60*1000 });
    }
    
    Connection getConnectionByInboundId(long id) {
        return _connectionByInboundId.get(Long.valueOf(id));
    }
    /** 
     * not guaranteed to be unique, but in case we receive more than one packet
     * on an inbound connection that we havent ack'ed yet...
     */
    Connection getConnectionByOutboundId(long id) {
            for (Connection con : _connectionByInboundId.values()) {
                if (con.getSendStreamId() == id)
                    return con;
            }
        return null;
    }
    
    /**
     * Set the socket accept() timeout.
     * @param x
     */
    public void setSoTimeout(long x) {
        _soTimeout = x;
    }

    /**
     * Get the socket accept() timeout.
     * @return accept timeout in ms.
     */
    public long getSoTimeout() {
        return _soTimeout;
    }

    public void setAllowIncomingConnections(boolean allow) { 
        _connectionHandler.setActive(allow);
        if (allow) {
            synchronized(this) {
                if (!_throttlersInitialized) {
                    updateOptions();
                    _throttlersInitialized = true;
                }
            }
        }
    }

    /*
     * Update the throttler options
     * @since 0.9.3
     */
    public synchronized void updateOptions() { 
            if ((_defaultOptions.getMaxConnsPerMinute() > 0 || _defaultOptions.getMaxTotalConnsPerMinute() > 0) &&
                _minuteThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledMinute", "Dropped for conn limit", "Stream", new long[] { 5*60*1000 });
               _minuteThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerMinute(), _defaultOptions.getMaxTotalConnsPerMinute(), 60*1000);
            } else if (_minuteThrottler != null) {
               _minuteThrottler.updateLimits(_defaultOptions.getMaxConnsPerMinute(), _defaultOptions.getMaxTotalConnsPerMinute());
            }
            if ((_defaultOptions.getMaxConnsPerHour() > 0 || _defaultOptions.getMaxTotalConnsPerHour() > 0) &&
                _hourThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledHour", "Dropped for conn limit", "Stream", new long[] { 5*60*1000 });
               _hourThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerHour(), _defaultOptions.getMaxTotalConnsPerHour(), 60*60*1000);
            } else if (_hourThrottler != null) {
               _hourThrottler.updateLimits(_defaultOptions.getMaxConnsPerHour(), _defaultOptions.getMaxTotalConnsPerHour());
            }
            if ((_defaultOptions.getMaxConnsPerDay() > 0 || _defaultOptions.getMaxTotalConnsPerDay() > 0) &&
                _dayThrottler == null) {
               _context.statManager().createRateStat("stream.con.throttledDay", "Dropped for conn limit", "Stream", new long[] { 5*60*1000 });
               _dayThrottler = new ConnThrottler(_defaultOptions.getMaxConnsPerDay(), _defaultOptions.getMaxTotalConnsPerDay(), 24*60*60*1000);
            } else if (_dayThrottler != null) {
               _dayThrottler.updateLimits(_defaultOptions.getMaxConnsPerDay(), _defaultOptions.getMaxTotalConnsPerDay());
            }
    }

    /** @return if we should accept connections */
    public boolean getAllowIncomingConnections() {
        return _connectionHandler.getActive();
    }
    
    /**
     * Create a new connection based on the SYN packet we received.
     *
     * @param synPacket SYN packet to process
     * @return created Connection with the packet's data already delivered to
     *         it, or null if the syn's streamId was already taken
     */
    public Connection receiveConnection(Packet synPacket) {
        ConnectionOptions opts = new ConnectionOptions(_defaultOptions);
        opts.setPort(synPacket.getRemotePort());
        opts.setLocalPort(synPacket.getLocalPort());
        Connection con = new Connection(_context, this, _schedulerChooser, _timer, _outboundQueue, _conPacketHandler, opts, true);
        _tcbShare.updateOptsFromShare(con);
        long receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
        boolean reject = false;
        int active = 0;
        int total = 0;

            // just for the stat
            //total = _connectionByInboundId.size();
            //for (Iterator iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
            //    if ( ((Connection)iter.next()).getIsConnected() )
            //        active++;
            //}
            if (locked_tooManyStreams()) {
                if ((!_defaultOptions.getDisableRejectLogging()) || _log.shouldLog(Log.WARN))
                    _log.logAlways(Log.WARN, "Refusing connection since we have exceeded our max of " 
                              + _defaultOptions.getMaxConns() + " connections");
                reject = true;
            } else {
                // this may not be right if more than one is enabled
                String why = shouldRejectConnection(synPacket);
                if (why != null) {
                    if ((!_defaultOptions.getDisableRejectLogging()) || _log.shouldLog(Log.WARN))
                        _log.logAlways(Log.WARN, "Refusing connection since peer is " + why +
                           (synPacket.getOptionalFrom() == null ? "" : ": " + synPacket.getOptionalFrom().calculateHash().toBase64()));
                    reject = true;
                } else { 
                    while (true) {
                        Connection oldCon = _connectionByInboundId.putIfAbsent(Long.valueOf(receiveId), con);
                        if (oldCon == null) {
                            break;
                        } else { 
                            // receiveId already taken, try another
                            receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                        }
                    }
                }
            }
        
        _context.statManager().addRateData("stream.receiveActive", active, total);
        
        if (reject) {
            Destination from = synPacket.getOptionalFrom();
            if (from == null)
                return null;
            if (_dayThrottler != null || _hourThrottler != null) {
                Hash h = from.calculateHash();
                if ((_hourThrottler != null && _hourThrottler.isThrottled(h)) ||
                    (_dayThrottler != null && _dayThrottler.isThrottled(h)) ||
                    _globalBlacklist.contains(h) ||
                    (_defaultOptions.isAccessListEnabled() && !_defaultOptions.getAccessList().contains(h)) ||
                    (_defaultOptions.isBlacklistEnabled() && _defaultOptions.getBlacklist().contains(h))) {
                    // A signed RST packet + ElGamal + session tags is fairly expensive, so
                    // once the hour/day limit is hit for a particular peer, don't even send it.
                    // Ditto for blacklist / whitelist
                    // This is a tradeoff, because it will keep retransmitting the SYN for a while,
                    // thus more inbound, but let's not spend several KB on the outbound.
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Dropping RST to " + h);
                    return null;
                }
            }
            PacketLocal reply = new PacketLocal(_context, from);
            reply.setFlag(Packet.FLAG_RESET);
            reply.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
            reply.setAckThrough(synPacket.getSequenceNum());
            reply.setSendStreamId(synPacket.getReceiveStreamId());
            reply.setReceiveStreamId(0);
            reply.setOptionalFrom(_session.getMyDestination());
            // this just sends the packet - no retries or whatnot
            _outboundQueue.enqueue(reply);
            return null;
        }
        
        con.setReceiveStreamId(receiveId);
        // finally, we know enough that we can log the packet with the conn filled in
        if (I2PSocketManagerFull.pcapWriter != null &&
            _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
            synPacket.logTCPDump(con);
        try {
            // This validates the packet, and sets the con's SendStreamID and RemotePeer
            con.getPacketHandler().receivePacket(synPacket, con);
        } catch (I2PException ie) {
            _connectionByInboundId.remove(Long.valueOf(receiveId));
            return null;
        }
        
        _context.statManager().addRateData("stream.connectionReceived", 1, 0);
        return con;
    }
    
    private static final long DEFAULT_STREAM_DELAY_MAX = 10*1000;
    
    /**
     * Build a new connection to the given peer.  This blocks if there is no
     * connection delay, otherwise it returns immediately.
     *
     * @param peer Destination to contact
     * @param opts Connection's options
     * @return new connection, or null if we have exceeded our limit
     */
    public Connection connect(Destination peer, ConnectionOptions opts) {
        Connection con = null;
        long receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
        long expiration = _context.clock().now() + opts.getConnectTimeout();
        if (opts.getConnectTimeout() <= 0)
            expiration = _context.clock().now() + DEFAULT_STREAM_DELAY_MAX;
        _numWaiting.incrementAndGet();
        while (true) {
            long remaining = expiration - _context.clock().now();
            if (remaining <= 0) { 
                _log.logAlways(Log.WARN, "Refusing to connect since we have exceeded our max of " 
                          + _defaultOptions.getMaxConns() + " connections");
                _numWaiting.decrementAndGet();
                return null;
            }

                if (locked_tooManyStreams()) {
                    int max = _defaultOptions.getMaxConns();
                    // allow a full buffer of pending/waiting streams
                    if (_numWaiting.get() > max) {
                        _log.logAlways(Log.WARN, "Refusing connection since we have exceeded our max of "
                                      + max + " and there are " + _numWaiting
                                      + " waiting already");
                        _numWaiting.decrementAndGet();
                        return null;
                    }

                    // no remaining streams, lets wait a bit
                    // got rid of the lock, so just sleep (fixme?)
                    // try { _connectionLock.wait(remaining); } catch (InterruptedException ie) {}
                    try { Thread.sleep(remaining/4); } catch (InterruptedException ie) {}
                } else { 
                    con = new Connection(_context, this, _schedulerChooser, _timer, _outboundQueue, _conPacketHandler, opts, false);
                    con.setRemotePeer(peer);
            
                    while (_connectionByInboundId.containsKey(Long.valueOf(receiveId))) {
                        receiveId = _context.random().nextLong(Packet.MAX_STREAM_ID-1)+1;
                    }
                    _connectionByInboundId.put(Long.valueOf(receiveId), con);
                    break; // stop looping as a psuedo-wait
                }

        }

        // ok we're in...
        con.setReceiveStreamId(receiveId);        
        con.eventOccurred();
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Connect() conDelay = " + opts.getConnectDelay());
        if (opts.getConnectDelay() <= 0) {
            con.waitForConnect();
        }
        // safe decrement
        for (;;) {
            int n = _numWaiting.get();
            if (n <= 0)
                break;
            if (_numWaiting.compareAndSet(n, n - 1))
                break;
        }
        
        _context.statManager().addRateData("stream.connectionCreated", 1, 0);
        return con;
    }

    /**
     *  Doesn't need to be locked any more
     *  @return too many
     */
    private boolean locked_tooManyStreams() {
        int max = _defaultOptions.getMaxConns();
        if (max <= 0) return false;
        int size = _connectionByInboundId.size();
        if (size < max) return false;
        // count both so we can break out of the for loop asap
        int active = 0;
        int inactive = 0;
        int maxInactive = size - max;
        for (Connection con : _connectionByInboundId.values()) {
            // ticket #1039
            if (con.getIsConnected() &&
                !(con.getCloseSentOn() > 0 && con.getCloseReceivedOn() > 0)) {
                if (++active >= max)
                    return true;
            } else {
                if (++inactive > maxInactive)
                    return false;
            }
        }
        
        //if ( (_connectionByInboundId.size() > 100) && (_log.shouldLog(Log.INFO)) )
        //    _log.info("More than 100 connections!  " + active
        //              + " total: " + _connectionByInboundId.size());

        return false;
    }
    
    /**
     *  @return reason string or null if not rejected
     */
    private String shouldRejectConnection(Packet syn) {
        // unfortunately we don't have access to the router client manager here,
        // so we can't whitelist local access
        Destination from = syn.getOptionalFrom();
        if (from == null)
            return "null";
        Hash h = from.calculateHash();

        // As of 0.9.9, run the blacklist checks BEFORE the port counters,
        // so blacklisted dests will not increment the counters and
        // possibly trigger total-counter blocks for others.

        // if the sig is absent or bad it will be caught later (in CPH)
        String hashes = _context.getProperty(PROP_BLACKLIST, "");
        if (!_currentBlacklist.equals(hashes)) {
            // rebuild _globalBlacklist when property changes
            synchronized(_globalBlacklist) {
                if (hashes.length() > 0) {
                    Set<Hash> newSet = new HashSet<Hash>();
                    StringTokenizer tok = new StringTokenizer(hashes, ",; ");
                    while (tok.hasMoreTokens()) {
                        String hashstr = tok.nextToken();
                        Hash hh = ConvertToHash.getHash(hashstr);
                        if (hh != null)
                            newSet.add(hh);
                        else
                            _log.error("Bad blacklist entry: " + hashstr);
                    }
                    _globalBlacklist.addAll(newSet);
                    _globalBlacklist.retainAll(newSet);
                    _currentBlacklist = hashes;
                } else {
                    _globalBlacklist.clear();
                    _currentBlacklist = "";
                }
            }
        }
        if (hashes.length() > 0 && _globalBlacklist.contains(h))
            return "blacklisted globally";

        if (_defaultOptions.isAccessListEnabled() &&
            !_defaultOptions.getAccessList().contains(h))
            return "not whitelisted";
        if (_defaultOptions.isBlacklistEnabled() &&
            _defaultOptions.getBlacklist().contains(h))
            return "blacklisted";


        if (_dayThrottler != null && _dayThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledDay", 1, 0);
            if (_defaultOptions.getMaxConnsPerDay() <= 0)
                return "throttled by" +
                        " total limit of " + _defaultOptions.getMaxTotalConnsPerDay() +
                        " per day";
            else if (_defaultOptions.getMaxTotalConnsPerDay() <= 0)
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerDay() +
                        " per day";
            else
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerDay() +
                        " or total limit of " + _defaultOptions.getMaxTotalConnsPerDay() +
                        " per day";
        }
        if (_hourThrottler != null && _hourThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledHour", 1, 0);
            if (_defaultOptions.getMaxConnsPerHour() <= 0)
                return "throttled by" +
                        " total limit of " + _defaultOptions.getMaxTotalConnsPerHour() +
                        " per hour";
            else if (_defaultOptions.getMaxTotalConnsPerHour() <= 0)
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerHour() +
                        " per hour";
            else
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerHour() +
                        " or total limit of " + _defaultOptions.getMaxTotalConnsPerHour() +
                        " per hour";
        }
        if (_minuteThrottler != null && _minuteThrottler.shouldThrottle(h)) {
            _context.statManager().addRateData("stream.con.throttledMinute", 1, 0);
            if (_defaultOptions.getMaxConnsPerMinute() <= 0)
                return "throttled by" +
                        " total limit of " + _defaultOptions.getMaxTotalConnsPerMinute() +
                        " per minute";
            else if (_defaultOptions.getMaxTotalConnsPerMinute() <= 0)
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerMinute() +
                        " per minute";
            else
                return "throttled by per-peer limit of " + _defaultOptions.getMaxConnsPerMinute() +
                        " or total limit of " + _defaultOptions.getMaxTotalConnsPerMinute() +
                        " per minute";
        }

        return null;
    }


    public MessageHandler getMessageHandler() { return _messageHandler; }
    public PacketHandler getPacketHandler() { return _packetHandler; }
    public I2PSession getSession() { return _session; }
    public void updateOptsFromShare(Connection con) { _tcbShare.updateOptsFromShare(con); }
    public void updateShareOpts(Connection con) { _tcbShare.updateShareOpts(con); }
    // Both of these methods are 
    // exporting non-public type through public API, this is a potential bug.
    public ConnectionHandler getConnectionHandler() { return _connectionHandler; }
    public PacketQueue getPacketQueue() { return _outboundQueue; }
    /** do we respond to pings that aren't on an existing connection? */
    public boolean answerPings() { return _defaultOptions.getAnswerPings(); }
    
    /**
     * Something b0rked hard, so kill all of our connections without mercy.
     * Don't bother sending close packets.
     *
     * CAN continue to use the manager.
     */
    public void disconnectAllHard() {
        for (Iterator<Connection> iter = _connectionByInboundId.values().iterator(); iter.hasNext(); ) {
            Connection con = iter.next();
            con.disconnect(false, false);
            iter.remove();
        }
    }
    
    /**
     * Kill all connections and the timers.
     * Don't bother sending close packets.
     *
     * CANNOT continue to use the manager or restart.
     *
     * @since 0.9.7
     */
    public void shutdown() {
        disconnectAllHard();
        _tcbShare.stop();
        _timer.stop();
    }
    
    /**
     * Drop the (already closed) connection on the floor.
     *
     * @param con Connection to drop.
     */
    public void removeConnection(Connection con) {

            Object o = _connectionByInboundId.remove(Long.valueOf(con.getReceiveStreamId()));
            boolean removed = (o == con);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Connection removed? " + removed + " remaining: " 
                           + _connectionByInboundId.size() + ": " + con);
            if (!removed && _log.shouldLog(Log.DEBUG))
                _log.debug("Failed to remove " + con +"\n" + _connectionByInboundId.values());

        if (removed) {
            _context.statManager().addRateData("stream.con.lifetimeMessagesSent", 1+con.getLastSendId(), con.getLifetime());
            MessageInputStream stream = con.getInputStream();
                long rcvd = 1 + stream.getHighestBlockId();
                long nacks[] = stream.getNacks();
                if (nacks != null)
                    rcvd -= nacks.length;
                _context.statManager().addRateData("stream.con.lifetimeMessagesReceived", rcvd, con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesSent", con.getLifetimeBytesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeBytesReceived", con.getLifetimeBytesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesSent", con.getLifetimeDupMessagesSent(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeDupMessagesReceived", con.getLifetimeDupMessagesReceived(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeRTT", con.getOptions().getRTT(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeCongestionSeenAt", con.getLastCongestionSeenAt(), con.getLifetime());
            _context.statManager().addRateData("stream.con.lifetimeSendWindowSize", con.getOptions().getWindowSize(), con.getLifetime());
            if (I2PSocketManagerFull.pcapWriter != null)
                I2PSocketManagerFull.pcapWriter.flush();
        }
    }
    
    /** return a set of Connection objects
     * @return set of Connection objects
     */
    public Set<Connection> listConnections() {
            return new HashSet<Connection>(_connectionByInboundId.values());
    }

    /** blocking */
    public boolean ping(Destination peer, long timeoutMs) {
        return ping(peer, timeoutMs, true, null);
    }
    public boolean ping(Destination peer, long timeoutMs, boolean blocking) {
        return ping(peer, timeoutMs, blocking, null);
    }

    /**
     * @deprecated I2PSession ignores tags, use non-tag variant
     * @param keyToUse ignored
     * @param tagsToSend ignored
     */
    public boolean ping(Destination peer, long timeoutMs, boolean blocking, SessionKey keyToUse, Set<?> tagsToSend, PingNotifier notifier) {
        return ping(peer, timeoutMs, blocking, notifier);
    }

    public boolean ping(Destination peer, long timeoutMs, boolean blocking, PingNotifier notifier) {
        Long id = Long.valueOf(_context.random().nextLong(Packet.MAX_STREAM_ID-1)+1);
        PacketLocal packet = new PacketLocal(_context, peer);
        packet.setSendStreamId(id.longValue());
        packet.setFlag(Packet.FLAG_ECHO);
        packet.setFlag(Packet.FLAG_SIGNATURE_INCLUDED);
        packet.setOptionalFrom(_session.getMyDestination());
        //if ( (keyToUse != null) && (tagsToSend != null) ) {
        //    packet.setKeyUsed(keyToUse);
        //    packet.setTagsSent(tagsToSend);
        //}
        if (_log.shouldLog(Log.INFO)) {
            _log.info(String.format("about to ping %s timeout=%d blocking=%b",
                    peer,timeoutMs,blocking));
        }
            
        
        PingRequest req = new PingRequest(notifier);
        
        _pendingPings.put(id, req);
        
        _outboundQueue.enqueue(packet);
        packet.releasePayload();
        
        if (blocking) {
            synchronized (req) {
                if (!req.pongReceived())
                    try { req.wait(timeoutMs); } catch (InterruptedException ie) {}
            }
            _pendingPings.remove(id);
        } else {
            PingFailed pf = new PingFailed(id, notifier);
            pf.schedule(timeoutMs);
        }
        
        boolean ok = req.pongReceived();
        return ok;
    }

    public interface PingNotifier {
        public void pingComplete(boolean ok);
    }
    
    private class PingFailed extends SimpleTimer2.TimedEvent {
        private final Long _id;
        private final PingNotifier _notifier;

        public PingFailed(Long id, PingNotifier notifier) { 
            super(_context.simpleTimer2());
            _id = id;
            _notifier = notifier;
        }
        
        public void timeReached() {
            PingRequest pr = _pendingPings.remove(_id);
            if (pr != null) {
                if (_notifier != null)
                    _notifier.pingComplete(false);
                if (_log.shouldLog(Log.INFO))
                    _log.info("Ping failed");
            }
        }
    }
    
    private static class PingRequest {
        private boolean _ponged;
        private final PingNotifier _notifier;

        public PingRequest(PingNotifier notifier) { 
            _notifier = notifier;
        }

        public void pong() { 
            // static, no log
            //_log.debug("Ping successful");
            //_context.sessionKeyManager().tagsDelivered(_peer.getPublicKey(), _packet.getKeyUsed(), _packet.getTagsSent());
            synchronized (this) {
                _ponged = true; 
                notifyAll();
            }
            if (_notifier != null)
                _notifier.pingComplete(true);
        }
        public synchronized boolean pongReceived() { return _ponged; }
    }
    
    void receivePong(long pingId) {
        PingRequest req = _pendingPings.remove(Long.valueOf(pingId));
        if (req != null) 
            req.pong();
    }
}
