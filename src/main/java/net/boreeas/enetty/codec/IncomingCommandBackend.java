package net.boreeas.enetty.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.boreeas.enetty.commands.*;

import java.util.function.Consumer;

/**
 * @author Malte Schütze
 */
public class IncomingCommandBackend extends ChannelInboundHandlerAdapter {
    private PeerIdGenerator peerIds;

    private PeerMap peers;
    private Consumer<Peer> newConnectionCallback;

    public IncomingCommandBackend(PeerIdGenerator peerIds, PeerMap peerMap, Consumer<Peer> newConnectionCallback) {
        this.peerIds = peerIds;
        this.peers = peerMap;
        this.newConnectionCallback = newConnectionCallback;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ENetCommand)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ENetCommand cmd = (ENetCommand) msg;
        int peerId = cmd.getHeader().getPeerId();
        Peer peer = peers.getByOutgoing(peerId);

        if (cmd instanceof Acknowledge) {
            // TODO RTT measurement
            peer.getAckPending().remove(((Acknowledge) cmd).getReceivedSeqNum());
        } else if (cmd instanceof BandwidthLimit) {
            // TODO bandwidth limit
        } else if (cmd instanceof Connect) {
            handleConnect(ctx, (Connect) cmd);
        } else if (cmd instanceof Disconnect) {
            peers.remove(peer);
            peerIds.returnId(peer.getPeerId());
        } else if (cmd instanceof Ping) {
            // Pings are ignored
        } else if (cmd instanceof SendFragment) {
            peer.getENetChannel(cmd.getChannelId()).onFragmentReceived((SendFragment) cmd);
        } else if (cmd instanceof SendReliable) {
            peer.getENetChannel(cmd.getChannelId()).onReliableReceived((SendReliable) cmd);
        } else if (cmd instanceof SendUnreliable) {
            peer.getENetChannel(cmd.getChannelId()).onUnreliableReceived((SendUnreliable) cmd);
        } else if (cmd instanceof SendUnsequenced) {
            peer.onUnsequencedReceived((SendUnsequenced) cmd);
        } else if (cmd instanceof ThrottleConfigure) {
            // TODO Throttle configure
        } else if (cmd instanceof VerifyConnect) {
            handleVerifyConnect(peer, ctx, (VerifyConnect) cmd);
        }

        if (cmd.getSendType() == ENetCommand.SendType.RELIABLE) {
            peer.getENetChannel(cmd.getChannelId()).acknowledge(cmd);
        }

        ctx.fireChannelRead(msg);
    }

    private void handleVerifyConnect(Peer peer, ChannelHandlerContext ctx, VerifyConnect cmd) {
        // TODO verify new params
        peer.setIncomingBandwidth(cmd.getIncomingBandwidth());
        peer.setOutgoingBandwidth(cmd.getOutgoingBandwidth());
        // TODO lookup peer id values in verify
        peer.setOurId(cmd.getOutgoingPeerId());
        peer.setPacketThrottleInterval(cmd.getPacketThrottleInterval());
        peer.setPacketThrottleAcceleration(cmd.getPacketThrottleAcceleration());
        peer.setPacketThrottleDeceleration(cmd.getPacketThrottleDeceleration());
        peer.setMtu(cmd.getMtu());
        peer.setChannelCount(cmd.getChannelCount());
        peer.setWindowSize(cmd.getWindowSize());

        // Needs to be added by connect call beforehand: outgoing peer id, netty channel
    }

    private void handleConnect(ChannelHandlerContext ctx, Connect cmd) {
        Peer peer = new Peer();
        peer.setChannel(ctx.channel());

        peer.setCurrentThrottleScore(0);
        peer.setCurrentThrottleValue(0);
        peer.setIncomingBandwidth(cmd.getOutgoingBandwidth());
        peer.setOutgoingBandwidth(cmd.getIncomingBandwidth());
        peer.setOurId(cmd.getOutgoingPeerId());
        try {
            peer.setPeerId(peerIds.next(1)); // 2 milliseconds timeout before failing
        } catch (InterruptedException e) {
            throw new IllegalStateException("Too many connected peers");
        }
        peer.setPacketThrottleAcceleration(cmd.getPacketThrottleAcceleration());
        peer.setPacketThrottleDeceleration(cmd.getPacketThrottleDeceleration());
        peer.setPacketThrottleInterval(cmd.getPacketThrottleInterval());
        peer.setSessionId((int) cmd.getSessionId());
        peer.setMtu(cmd.getMtu());
        peer.setChannelCount(cmd.getChannelCount());
        peer.setWindowSize(cmd.getWindowSize());

        peers.add(peer);
        newConnectionCallback.accept(peer);

        // TODO actually verify connection parameters
        ENetProtocolHeader header = new ENetProtocolHeader(0, true, peer.getOurId(), peer.connectionTime());
        VerifyConnect verification = new VerifyConnect(header, 0xff, 0, peer.getPeerId(), peer.getMtu(), peer.getWindowSize(), peer.getChannelCount(),
                peer.getIncomingBandwidth(), peer.getOutgoingBandwidth(), peer.getPacketThrottleInterval(), peer.getPacketThrottleAcceleration(), peer.getPacketThrottleDeceleration());

        peer.getENetChannel(0xff).writePacket(verification);
    }
}
