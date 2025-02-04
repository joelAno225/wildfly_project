/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.clustering.jgroups;

import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.as.network.SocketBinding;
import org.jgroups.EmptyMessage;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.blocks.RequestCorrelator;
import org.jgroups.blocks.RequestCorrelator.Header;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.fork.UnknownForkHandler;
import org.jgroups.protocols.FORK;
import org.jgroups.protocols.TP;
import org.jgroups.stack.Protocol;
import org.wildfly.clustering.jgroups.spi.ChannelFactory;
import org.wildfly.clustering.jgroups.spi.ProtocolConfiguration;
import org.wildfly.clustering.jgroups.spi.ProtocolStackConfiguration;
import org.wildfly.clustering.jgroups.spi.TransportConfiguration;

/**
 * Factory for creating fork-able channels.
 * @author Paul Ferraro
 */
public class JChannelFactory implements ChannelFactory {

    private final ProtocolStackConfiguration configuration;

    public JChannelFactory(ProtocolStackConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public ProtocolStackConfiguration getProtocolStackConfiguration() {
        return this.configuration;
    }

    @Override
    public JChannel createChannel(String id) throws Exception {
        FORK fork = new FORK();
        fork.enableStats(this.configuration.isStatisticsEnabled());
        fork.setUnknownForkHandler(new UnknownForkHandler() {
            private final short id = ClassConfigurator.getProtocolId(RequestCorrelator.class);

            @Override
            public Object handleUnknownForkStack(Message message, String forkStackId) {
                return this.handle(message);
            }

            @Override
            public Object handleUnknownForkChannel(Message message, String forkChannelId) {
                return this.handle(message);
            }

            private Object handle(Message message) {
                Header header = (Header) message.getHeader(this.id);
                // If this is a request expecting a response, don't leave the requester hanging - send an identifiable response on which it can filter
                if ((header != null) && (header.type == Header.REQ) && header.rspExpected()) {
                    Message response = new EmptyMessage(message.src()).setFlag(message.getFlags(), false).clearFlag(Message.Flag.RSVP);
                    if (message.getDest() != null) {
                        response.src(message.getDest());
                    }

                    response.putHeader(FORK.ID, message.getHeader(FORK.ID));
                    response.putHeader(this.id, new Header(Header.RSP, header.req_id, header.corrId));

                    fork.getProtocolStack().getChannel().down(response);
                }
                return null;
            }
        });

        Map<String, SocketBinding> bindings = new HashMap<>();
        // Transport always resides at the bottom of the stack
        List<ProtocolConfiguration<? extends Protocol>> transports = Collections.singletonList(this.configuration.getTransport());
        // Add RELAY2 to the top of the stack, if defined
        List<ProtocolConfiguration<? extends Protocol>> relays = this.configuration.getRelay().isPresent() ? Collections.singletonList(this.configuration.getRelay().get()) : Collections.emptyList();
        List<Protocol> protocols = new ArrayList<>(transports.size() + this.configuration.getProtocols().size() + relays.size() + 1);
        for (List<ProtocolConfiguration<? extends Protocol>> protocolConfigs : List.of(transports, this.configuration.getProtocols(), relays)) {
            for (ProtocolConfiguration<? extends Protocol> protocolConfig : protocolConfigs) {
                protocols.add(protocolConfig.createProtocol(this.configuration));
                bindings.putAll(protocolConfig.getSocketBindings());
            }
        }
        // Add implicit FORK to the top of the stack
        protocols.add(fork);

        // Override the SocketFactory of the transport
        TP transport = (TP) protocols.get(0);
        transport.setSocketFactory(new ManagedSocketFactory(SelectorProvider.provider(), this.configuration.getSocketBindingManager(), bindings, transport.getThreadPool()::execute));

        JChannel channel = new JChannel(protocols);

        channel.setName(this.configuration.getNodeName());

        TransportConfiguration.Topology topology = this.configuration.getTransport().getTopology();
        if (topology != null) {
            channel.addAddressGenerator(new TopologyAddressGenerator(topology));
        }

        return channel;
    }

    @Override
    public boolean isUnknownForkResponse(Message response) {
        return !response.hasPayload();
    }
}
