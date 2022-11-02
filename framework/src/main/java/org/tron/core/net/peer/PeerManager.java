package org.tron.core.net.peer;

import com.google.common.collect.Lists;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.tron.p2p.connection.Channel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j(topic = "net")
public class PeerManager {

  private static List<PeerConnection> peers = Collections.synchronizedList(new ArrayList<>());
  @Getter
  private static AtomicInteger passivePeersCount = new AtomicInteger(0);
  @Getter
  private static AtomicInteger activePeersCount = new AtomicInteger(0);

  private static ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

  private static long DISCONNECTION_TIME_OUT = 60_000;

  public static void init() {
    executor.scheduleWithFixedDelay(() -> {
      try {
        check();
      } catch (Throwable t) {
        logger.error("Exception in peer manager", t);
      }
    }, 100, 3600, TimeUnit.MILLISECONDS);
  }

  public static void close() {
    try {
      peers.forEach(p -> {
        if (!p.isDisconnect()) {
          p.getChannel().close();
        }
      });
      executor.shutdownNow();
    } catch (Exception e) {
      logger.error("Peer manager shutdown failed", e);
    }
  }

  public synchronized static PeerConnection add(ApplicationContext ctx, Channel channel) {
    PeerConnection peerConnection = getPeerConnection(channel);
    if (peerConnection != null) {
      return null;
    }
    peerConnection = ctx.getBean(PeerConnection.class);
    peerConnection.setChannel(channel);
    peers.add(peerConnection);
    if (channel.isActive()) {
      activePeersCount.incrementAndGet();
    } else {
      passivePeersCount.incrementAndGet();
    }
    return peerConnection;
  }

  public synchronized static PeerConnection remove(Channel channel) {
    PeerConnection peerConnection = getPeerConnection(channel);
    if (peerConnection == null) {
      return null;
    }
    remove(peerConnection);
    return peerConnection;
  }

  public synchronized static void sortPeers(){
    peers.sort(Comparator.comparingDouble(c -> c.getChannel().getLatency()));
  }

  public static PeerConnection getPeerConnection(Channel channel) {
    for(PeerConnection peer: new ArrayList<>(peers)) {
      if (peer.getChannel().equals(channel)) {
        return peer;
      }
    }
    return null;
  }

  public static List<PeerConnection> getPeers() {
    List<PeerConnection> peers = Lists.newArrayList();
    for (PeerConnection peer : new ArrayList<>(PeerManager.peers)) {
      if (!peer.isDisconnect()) {
        peers.add(peer);
      }
    }
    return peers;
  }

  private static void remove(PeerConnection peerConnection) {
    peers.remove(peerConnection);
    if (peerConnection.getChannel().isActive()) {
      activePeersCount.decrementAndGet();
    } else {
      passivePeersCount.decrementAndGet();
    }
  }

  private static void check() {
    long now = System.currentTimeMillis();
    for (PeerConnection peer : new ArrayList<>(peers)) {
      long disconnectTime = peer.getChannel().getDisconnectTime();
      if (disconnectTime != 0 && now - disconnectTime > DISCONNECTION_TIME_OUT) {
        logger.warn("Notify disconnect peer {}.", peer.getInetSocketAddress());
        peers.remove(peer);
        if (peer.getChannel().isActive()) {
          activePeersCount.decrementAndGet();
        } else {
          passivePeersCount.decrementAndGet();
        }
        peer.onDisconnect();
      }
    }
  }

}
