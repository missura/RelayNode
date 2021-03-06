package com.mattcorallo.relaynode;

import com.google.bitcoin.core.*;
import com.google.bitcoin.net.NioServer;
import com.google.bitcoin.net.StreamParser;
import com.google.bitcoin.net.StreamParserFactory;
import com.google.bitcoin.params.MainNetParams;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class RelayConnectionListener {
	//TODO: Track memory usage and kill hungry connections

	private final Set<RelayConnection> connectionSet = Collections.synchronizedSet(new LinkedHashSet<RelayConnection>());
	private final Set<InetAddress> remoteSet = Collections.synchronizedSet(new HashSet<InetAddress>());

	private final Cache<InetAddress, Integer> oldHosts = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS).maximumSize(100).build();

	public RelayConnectionListener(int port, @Nonnull final PeerEventListener clientPeerListener, @Nonnull final RelayNode lineLogger) throws IOException {
		final NetworkParameters params = MainNetParams.get();
		final VersionMessage fakeVersionMessage = new VersionMessage(params, -1); // Used to identify connection as relay protocol
		fakeVersionMessage.appendToSubVer("RelayNodeProtocol", "", null);

		NioServer relayServer = new NioServer(new StreamParserFactory() {
			@Nullable
			@Override
			public StreamParser getNewParser(final InetAddress inetAddress, int port) {
				if (remoteSet.contains(inetAddress))
					return null;

				final Peer emulatedPeer = new Peer(params, fakeVersionMessage, null, new PeerAddress(inetAddress));

				return new RelayConnection(false) {
					@Override
					void LogLine(String line) {
						if (line.startsWith("Connected to node with ")) {
							if (oldHosts.asMap().get(inetAddress) != null)
								return;
							else
								oldHosts.put(inetAddress, 42);
						}
						lineLogger.LogLine(inetAddress.getHostAddress() + ": " + line);
					}

					@Override void LogStatsRecv(@Nonnull String lines) { }

					@Override void LogConnected(String line) { }

					@Override
					void receiveBlockHeader(Block b) { }

					@Override
					void receiveBlock(Block b) {
						clientPeerListener.onPreMessageReceived(emulatedPeer, b);
					}

					@Override
					void receiveTransaction(Transaction t) {
						clientPeerListener.onPreMessageReceived(emulatedPeer, t);
					}

					@Override
					public void connectionClosed() {
						connectionSet.remove(this);
						remoteSet.remove(inetAddress);
					}

					@Override
					public void connectionOpened() {
						connectionSet.add(this);
						remoteSet.add(inetAddress);
					}
				};
			}
		}, new InetSocketAddress(port));
		relayServer.startAsync().awaitRunning();
	}

	public void sendTransaction(@Nonnull Transaction t) {
		synchronized (connectionSet) {
			for (RelayConnection connection : connectionSet)
				connection.sendTransaction(t);
		}
	}

	public void sendBlock(@Nonnull final Block b) {
		synchronized (connectionSet) {
			for (RelayConnection connection : connectionSet)
				connection.sendBlock(b);
		}
	}

	@Nonnull
	public Set<InetAddress> getClientSet() {
		synchronized (remoteSet) {
			return new HashSet<>(remoteSet);
		}
	}
}
