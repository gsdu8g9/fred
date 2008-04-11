/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Vector;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.l10n.L10n;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.ByteArrayWrapper;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.io.Closer;
import freenet.support.transport.ip.IPUtil;

/**
 * Decide whether to announce, and announce if necessary to a node in the
 * routing table, or to a seednode.
 * @author toad
 */
public class Announcer {

	private static boolean logMINOR;
	private final Node node;
	private final OpennetManager om;
	private static int status;
	private static final int STATUS_LOADING = 0;
	private static final int STATUS_CONNECTING_SEEDNODES = 1;
	private static final int STATUS_NO_SEEDNODES = -1;
	private int runningAnnouncements;
	/** We want to announce to 3 different seednodes. */
	private static final int WANT_ANNOUNCEMENTS = 3;
	private int sentAnnouncements;
	private long startTime;
	private long timeAddedSeeds;
	static final long MIN_ADDED_SEEDS_INTERVAL = 60*1000;
	/** After we have sent 3 announcements, wait for 1 minute before sending 3 more if we still have no connections. */
	static final int COOLING_OFF_PERIOD = 60*1000;
	/** Identities of nodes we have announced to */
	private final HashSet announcedToIdentities;
	/** IPs of nodes we have announced to. Maybe this should be first-two-bytes, but I'm not sure how to do that with IPv6. */
	private final HashSet announcedToIPs;
	/** How many nodes to connect to at once? */
	static final int CONNECT_AT_ONCE = 10;
	/** Do not announce if there are more than this many opennet peers connected */
	private static final int MIN_OPENNET_CONNECTED_PEERS = 10;
	private static final long NOT_ALL_CONNECTED_DELAY = 60*1000;
	/** Identities of nodes we have tried to connect to */
	private final HashSet connectedToIdentities;
	/** Total nodes added by announcement so far */
	private int announcementAddedNodes;
	/** Total nodes that didn't want us so far */
	private int announcementNotWantedNodes;
	
	Announcer(OpennetManager om) {
		this.om = om;
		this.node = om.node;
		announcedToIdentities = new HashSet();
		announcedToIPs = new HashSet();
		connectedToIdentities = new HashSet();
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
	}

	public void start() {
		if(!node.isOpennetEnabled()) return;
		registerAlert();
		if(node.peers.getDarknetPeers().length + node.peers.getOpennetPeers().length + om.countOldOpennetPeers() == 0) {
			// We know opennet is enabled.
			// We have no peers AT ALL.
			// So lets connect to a few seednodes, and attempt an announcement.
			System.err.println("Attempting announcement to seednodes...");
			synchronized(this) {
				status = STATUS_LOADING;
			}
			connectSomeSeednodes();
		} else {
			// Wait a minute, then check whether we need to seed.
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					try {
						maybeSendAnnouncement();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t+" trying to send announcements", t);
					}
				}
			}, MIN_ADDED_SEEDS_INTERVAL);
		}
	}
	
	private void registerAlert() {
		node.clientCore.alerts.register(new AnnouncementUserAlert());
	}

	private void connectSomeSeednodes() {
		if(!node.isOpennetEnabled()) return;
		boolean announceNow = false;
		if(logMINOR)
			Logger.minor(this, "Connecting some seednodes...");
		Vector/*<SimpleFieldSet>*/ seeds = readSeednodes();
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(now - timeAddedSeeds < MIN_ADDED_SEEDS_INTERVAL) return;
			timeAddedSeeds = now;
			if(seeds.size() == 0) {
				status = STATUS_NO_SEEDNODES;
				return;
			} else {
				status = STATUS_CONNECTING_SEEDNODES;
			}
		}
		// Try to connect to some seednodes.
		// Once they are connected they will report back and we can attempt an announcement.

		int count = connectSomeNodesInner(seeds);
		synchronized(this) {
			if(logMINOR)
				Logger.minor(this, "count = "+count+" connected = "+connectedToIdentities.size()+
						" announced = "+announcedToIdentities.size()+" running = "+runningAnnouncements);
			if(count == 0 && runningAnnouncements == 0) {
				if(connectedToIdentities.size() > announcedToIdentities.size()) {
					// Some seednodes we haven't been able to connect to yet.
					// Give it another minute, then clear all and try again.
					if(logMINOR)
						Logger.minor(this, "Will clear announced-to in 1 minute...");
					node.getTicker().queueTimedJob(new Runnable() {
						public void run() {
							if(logMINOR)
								Logger.minor(this, "Clearing old announced-to list");
							synchronized(Announcer.this) {
								if(runningAnnouncements != 0) return;
								announcedToIdentities.clear();
								announcedToIPs.clear();
								connectedToIdentities.clear();
							}
							maybeSendAnnouncement();
						}
					}, NOT_ALL_CONNECTED_DELAY);
				} else if(connectedToIdentities.size() == announcedToIdentities.size()) {
					// Clear it now
					announcedToIdentities.clear();
					announcedToIPs.clear();
					connectedToIdentities.clear();
					announceNow = true;
				}
			}
		}
		// If none connect in a minute, try some more.
		node.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				try {
					maybeSendAnnouncement();
				} catch (Throwable t) {
					Logger.error(this, "Caught "+t+" trying to send announcements", t);
				}
			}
		}, announceNow ? 0 : MIN_ADDED_SEEDS_INTERVAL);
	}

	// Synchronize to protect announcedToIdentities and prevent running in parallel.
	private synchronized int connectSomeNodesInner(Vector seeds) {
		if(logMINOR)
			Logger.minor(this, "Connecting some seednodes from "+seeds.size());
		int count = 0;
		while(count < CONNECT_AT_ONCE) {
			if(seeds.size() == 0) break;
			SimpleFieldSet fs = (SimpleFieldSet) seeds.remove(node.random.nextInt(seeds.size()));
			try {
				SeedServerPeerNode seed =
					new SeedServerPeerNode(fs, node, om.crypto, node.peers, false, om.crypto.packetMangler);
				if(announcedToIdentities.contains(new ByteArrayWrapper(seed.identity))) {
					if(logMINOR)
						Logger.minor(this, "Not adding: already announced-to: "+seed.userToString());
					continue;
				}
				if(logMINOR)
					Logger.minor(this, "Trying to connect to seednode "+seed);
				if(node.peers.addPeer(seed)) {
					count++;
					connectedToIdentities.add(new ByteArrayWrapper(seed.identity));
					if(logMINOR)
						Logger.minor(this, "Connecting to seednode "+seed);
				} else {
					if(logMINOR)
						Logger.minor(this, "Not connecting to seednode "+seed);
				}
			} catch (FSParseException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			} catch (PeerParseException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			} catch (ReferenceSignatureVerificationException e) {
				Logger.error(this, "Invalid seed in file: "+e+" for\n"+fs, e);
				continue;
			}
		}
		return count;
	}

	private Vector readSeednodes() {
		if(logMINOR)
			Logger.minor(this, "Reading seednodes");
		File file = new File(node.nodeDir, "seednodes.fref");
		Vector list = new Vector();
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis, "UTF-8");
			BufferedReader br = new BufferedReader(isr);
			while(true) {
				try {
					SimpleFieldSet fs = new SimpleFieldSet(br, false, false);
					if(!fs.isEmpty())
						list.add(fs);
				} catch (EOFException e) {
					return list;
				}
			}
		} catch (IOException e) {
			return list;
		} finally {
			Closer.close(fis);
		}
	}

	public void stop() {
		// Do nothing at present
	}
	
	private long timeGotEnoughPeers = -1;
	
	private boolean killedAnnouncementTooOld;
	
	/** @return True if we have enough peers that we don't need to announce. */
	boolean enoughPeers() {
		// Do we want to send an announcement to the node?
		int opennetCount = node.peers.countConnectedOpennetPeers();
		// First, do we actually need to announce?
		int target = Math.min(MIN_OPENNET_CONNECTED_PEERS, om.getNumberOfConnectedPeersToAim() / 2);
		if(opennetCount >= target) {
			if(logMINOR)
				Logger.minor(this, "We have enough opennet peers: "+opennetCount+" > "+target+" since "+(System.currentTimeMillis()-timeGotEnoughPeers)+" ms");
			synchronized(this) {
				if(timeGotEnoughPeers <= 0)
					timeGotEnoughPeers = System.currentTimeMillis();
			}
			return true;
		}
		if(node.nodeUpdater == null || (!node.nodeUpdater.isEnabled()) ||
				(node.nodeUpdater.canUpdateNow() && !node.nodeUpdater.isArmed())) {
			// If we also have 10 TOO_NEW peers, we should shut down the announcement,
			// because we're obviously broken and would only be spamming the seednodes
			if(node.peers.getPeerNodeStatusSize(PeerManager.PEER_NODE_STATUS_TOO_NEW, true) +
					node.peers.getPeerNodeStatusSize(PeerManager.PEER_NODE_STATUS_TOO_NEW, false) > 10) {
				synchronized(this) {
					if(killedAnnouncementTooOld) return true;
					killedAnnouncementTooOld = true;
				}
				Logger.error(this, "Shutting down announcement as we are older than the current mandatory build and auto-update is disabled or waiting for user input.");
				System.err.println("Shutting down announcement as we are older than the current mandatory build and auto-update is disabled or waiting for user input.");
				if(node.clientCore != null)
					node.clientCore.alerts.register(new SimpleUserAlert(false, l10n("announceDisabledTooOldTitle"), l10n("announceDisabledTooOld"), UserAlert.CRITICAL_ERROR));
				return true;
			}
				
		}
		synchronized(this) {
			timeGotEnoughPeers = -1;
		}
		return false;
	}
	
	/**
	 * Get the earliest time at which we had enough opennet peers. This is reset when we drop
	 * below the threshold.
	 */
	synchronized long timeGotEnoughPeers() {
		return timeGotEnoughPeers;
	}

	private boolean ignoreIPUndetected;
	static final int FORCE_ANNOUNCEMENT_NO_IP = 120*1000;
	/** 1 minute after we have enough peers, remove all seednodes left (presumably disconnected ones) */
	static final int FINAL_DELAY = 60*1000;
	/** But if we don't have enough peers at that point, wait another minute and if the situation has not improved, reannounce. */
	static final int RETRY_DELAY = 60*1000;
	private boolean started = false;
	
	public void maybeSendAnnouncement() {
		started = true;
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR)
			Logger.minor(this, "maybeSendAnnouncement()");
		long now = System.currentTimeMillis();
		if(!node.isOpennetEnabled()) return;
		if(enoughPeers()) {
			node.getTicker().queueTimedJob(new Runnable() {
				public void run() {
					synchronized(Announcer.this) {
						if(runningAnnouncements > 0) return;
					}
					if(enoughPeers()) {
						Vector seeds = node.peers.getConnectedSeedServerPeersVector(null);
						for(int i=0;i<seeds.size();i++) {
							SeedServerPeerNode pn = (SeedServerPeerNode) seeds.get(i);
							node.peers.disconnect(pn, true, true);
						}
					} else {
						node.getTicker().queueTimedJob(new Runnable() {
							public void run() {
								maybeSendAnnouncement();
							}
						}, RETRY_DELAY);
						maybeSendAnnouncement();
					}
				}
			}, FINAL_DELAY);
			return;
		}
		if((!ignoreIPUndetected) && (!node.ipDetector.hasValidIP())) {
			if(node.ipDetector.ipDetectorManager.hasDetectors()) {
				// Wait a bit
				node.getTicker().queueTimedJob(new Runnable() {
					public void run() {
						synchronized(Announcer.this) {
							if(ignoreIPUndetected) return;
							ignoreIPUndetected = true;
						}
						maybeSendAnnouncement();
					}
				}, FORCE_ANNOUNCEMENT_NO_IP);
				return;
			}
		}
		synchronized(this) {
			// Double check after taking the lock.
			if(enoughPeers()) return;
			// Second, do we have many announcements running?
			if(runningAnnouncements > WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Running announcements already");
				return;
			}
			// In cooling-off period?
			if(System.currentTimeMillis() < startTime) {
				if(logMINOR)
					Logger.minor(this, "In cooling-off period for next "+TimeUtil.formatTime(startTime - System.currentTimeMillis()));
				return;
			}
			if(sentAnnouncements >= WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Sent enough announcements");
				return;
			}
			// Now find a node to announce to
			Vector seeds = node.peers.getConnectedSeedServerPeersVector(announcedToIdentities);
			while(sentAnnouncements < WANT_ANNOUNCEMENTS) {
				if(seeds.isEmpty()) {
					if(logMINOR)
						Logger.minor(this, "No more seednodes, announcedTo = "+announcedToIdentities.size());
					break;
				}
				final SeedServerPeerNode seed = (SeedServerPeerNode) seeds.remove(node.random.nextInt(seeds.size()));
				InetAddress[] addrs = seed.getInetAddresses();
				if(!newAnnouncedIPs(addrs)) {
					if(logMINOR)
						Logger.minor(this, "Not announcing to "+seed+" because already used those IPs");
					continue;
				}
				addAnnouncedIPs(addrs);
				sentAnnouncements++;
				runningAnnouncements++;
				announcedToIdentities.add(new ByteArrayWrapper(seed.getIdentity()));
				sendAnnouncement(seed);
			}
			if(runningAnnouncements >= WANT_ANNOUNCEMENTS) {
				if(logMINOR)
					Logger.minor(this, "Running "+runningAnnouncements+" announcements");
				return;
			}
			// Do we want to connect some more seednodes?
			if(now - timeAddedSeeds < MIN_ADDED_SEEDS_INTERVAL) {
				// Don't connect seednodes yet
				Logger.minor(this, "Waiting for MIN_ADDED_SEEDS_INTERVAL");
				node.getTicker().queueTimedJob(new Runnable() {
					public void run() {
						try {
							maybeSendAnnouncement();
						} catch (Throwable t) {
							Logger.error(this, "Caught "+t+" trying to send announcements", t);
						}
					}
				}, (timeAddedSeeds + MIN_ADDED_SEEDS_INTERVAL) - now);
				return;
			}
		}
		connectSomeSeednodes();
	}
	
	private synchronized void addAnnouncedIPs(InetAddress[] addrs) {
		for(int i=0;i<addrs.length;i++)
			announcedToIPs.add(addrs[i]);
	}

	private synchronized boolean newAnnouncedIPs(InetAddress[] addrs) {
		for(int i=0;i<addrs.length;i++) {
			if(!IPUtil.isValidAddress(addrs[i], false))
				continue;
			if(!announcedToIPs.contains(addrs[i]))
				return true;
		}
		return false;
	}

	public void sendAnnouncement(final SeedServerPeerNode seed) {
		if(!node.isOpennetEnabled()) return;
		System.out.println("Announcement to "+seed.userToString()+" starting...");
		if(logMINOR)
			Logger.minor(this, "Announcement to "+seed.userToString()+" starting...");
		AnnounceSender sender = new AnnounceSender(node.getLocation(), om, node, new AnnouncementCallback() {
			private int totalAdded;
			private int totalNotWanted;
			public void addedNode(PeerNode pn) {
				synchronized(Announcer.this) {
					announcementAddedNodes++;
					totalAdded++;
				}
				Logger.error(this, "Announcement to "+seed.userToString()+" added node "+pn+" for a total of "+announcementAddedNodes+" ("+totalAdded+" from this announcement)");
				System.out.println("Announcement to "+seed.userToString()+" added node "+pn.userToString()+'.');
				return;
			}
			public void bogusNoderef(String reason) {
				Logger.error(this, "Announcement to "+seed.userToString()+" got bogus noderef: "+reason, new Exception("debug"));
			}
			public void completed() {
				synchronized(Announcer.this) {
					runningAnnouncements--;
					Logger.error(this, "Announcement to "+seed.userToString()+" completed, now running "+runningAnnouncements+" announcements");
					if(runningAnnouncements == 0) {
						startTime = System.currentTimeMillis() + COOLING_OFF_PERIOD;
						sentAnnouncements = 0;
						// Wait for COOLING_OFF_PERIOD before trying again
						node.getTicker().queueTimedJob(new Runnable() {

							public void run() {
								maybeSendAnnouncement();
							}
							
						}, COOLING_OFF_PERIOD);
					}
				}
				// If it takes more than COOLING_OFF_PERIOD to disconnect, we might not be able to reannounce to this
				// node. However, we can't reannounce to it anyway until announcedTo is cleared, which probably will
				// be more than that period in the future.
				node.peers.disconnect(seed, true, false);
				System.out.println("Announcement to "+seed.userToString()+" completed.");
			}

			public void nodeFailed(PeerNode pn, String reason) {
				Logger.error(this, "Announcement to node "+pn.userToString()+" failed: "+reason);
			}
			public void noMoreNodes() {
				Logger.error(this, "Announcement to "+seed.userToString()+" ran out of nodes (route not found)");
			}
			public void nodeNotWanted() {
				synchronized(Announcer.this) {
					announcementNotWantedNodes++;
					totalNotWanted++;
				}
				Logger.error(this, "Announcement to "+seed.userToString()+" returned node not wanted for a total of "+announcementNotWantedNodes+" ("+totalNotWanted+" from this announcement)");
			}
			public void nodeNotAdded() {
				Logger.error(this, "Announcement to "+seed.userToString()+" : node not wanted (maybe already have it, opennet just turned off, etc)");
			}
		}, seed);
		node.executor.execute(sender, "Announcer to "+seed);
	}

	public class AnnouncementUserAlert implements UserAlert {

		public String dismissButtonText() {
			return L10n.getString("UserAlert.hide");
		}

		public HTMLNode getHTMLText() {
			return new HTMLNode("#", getText());
		}

		public short getPriorityClass() {
			return UserAlert.ERROR;
		}

		public String getText() {
			StringBuffer sb = new StringBuffer();
			sb.append(l10n("announceAlertIntro"));
			int status;
			synchronized(this) {
				status = Announcer.this.status;
			}
			if(status == STATUS_NO_SEEDNODES) {
				return l10n("announceAlertNoSeednodes");
			}
			if(status == STATUS_LOADING) {
				return l10n("announceLoading");
			}
			if(node.clientCore.isAdvancedModeEnabled()) {
				// Detail
				sb.append(' ');
				int addedNodes;
				int refusedNodes;
				int recentSentAnnouncements;
				int runningAnnouncements;
				int connectedSeednodes = 0;
				int disconnectedSeednodes = 0;
				long coolingOffSeconds = Math.max(0, startTime - System.currentTimeMillis()) / 1000;
				synchronized(this) {
					addedNodes = announcementAddedNodes;
					refusedNodes = announcementNotWantedNodes;
					recentSentAnnouncements = sentAnnouncements;
					runningAnnouncements = Announcer.this.runningAnnouncements;
					
				}
				Vector nodes = node.peers.getSeedServerPeersVector();
				for(int i=0;i<nodes.size();i++) {
					SeedServerPeerNode seed = (SeedServerPeerNode) nodes.get(i);
					if(seed.isConnected())
						connectedSeednodes++;
					else
						disconnectedSeednodes++;
				}
				sb.append(l10n("announceDetails", 
						new String[] { "addedNodes", "refusedNodes", "recentSentAnnouncements", "runningAnnouncements", "connectedSeednodes", "disconnectedSeednodes" },
						new String[] {
						Integer.toString(addedNodes),
						Integer.toString(refusedNodes),
						Integer.toString(recentSentAnnouncements),
						Integer.toString(runningAnnouncements),
						Integer.toString(connectedSeednodes),
						Integer.toString(disconnectedSeednodes)
				}));
				if(coolingOffSeconds > 0) {
					sb.append(' ');
					sb.append(l10n("coolingOff", "time", Long.toString(coolingOffSeconds)));
				}
			}
			return sb.toString();
		}

		public String getTitle() {
			return l10n("announceAlertTitle");
		}

		public Object getUserIdentifier() {
			return null;
		}

		public boolean isValid() {
			return started && (!enoughPeers()) && node.isOpennetEnabled();
		}

		public void isValid(boolean validity) {
			// Ignore
		}

		public void onDismiss() {
			// Ignore
		}

		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		public boolean userCanDismiss() {
			return true;
		}

	}
	
	private String l10n(String key) {
		return L10n.getString("Announcer."+key);
	}

	public String l10n(String key, String[] patterns, String[] values) {
		return L10n.getString("Announcer."+key, patterns, values);
	}

	public String l10n(String key, String pattern, String value) {
		return L10n.getString("Announcer."+key, pattern, value);
	}

}
