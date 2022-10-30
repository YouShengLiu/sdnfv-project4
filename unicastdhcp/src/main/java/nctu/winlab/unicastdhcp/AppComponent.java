/*
 * Copyright 2022-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.unicastdhcp;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import java.util.Set;
import java.util.List;
import java.util.Iterator;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.UDP;
import org.onlab.packet.TpPort;
import org.onosproject.core.CoreService;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.intent.PointToPointIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.ElementId;
import org.onosproject.net.Link;
import org.onosproject.net.FilteredConnectPoint;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.Path;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.PathService;
import org.onosproject.net.host.HostService;
import org.onosproject.net.PortNumber;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger("Unicast DHCP");
    private final DhcpConfigListener cfgListener = new DhcpConfigListener();
    private final ConfigFactory<ApplicationId, DhcpConfig> factory = 
        new ConfigFactory<ApplicationId, DhcpConfig>(APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
            @Override
            public DhcpConfig createConfig() {
                return new DhcpConfig();
            }
        };

    /* For registering the application */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    
    /* For handling the packet */
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry netCfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PathService pathService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected IntentService intentService;


    /* Variables */
    private ApplicationId appId;
    private MyPacketProcessor processor = new MyPacketProcessor();
    private ConnectPoint dhcp_server_cp;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");
        netCfgService.addListener(cfgListener);
        netCfgService.registerConfigFactory(factory);
        packetService.addProcessor(processor, PacketProcessor.director(2));

        requestPacket();
        log.info("Started {}", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        netCfgService.removeListener(cfgListener);
        netCfgService.unregisterConfigFactory(factory);

        cancelRequestPacket();
        cleanIntents();
        log.info("Stopped");
    }

    /* Request packet */
    private void requestPacket() {
        // Request for DHCPDISCOVER, DHCPREQUEST
        TrafficSelector.Builder dhcpClient =  DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPProtocol(IPv4.PROTOCOL_UDP)
        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
        .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(dhcpClient.build(), PacketPriority.REACTIVE, appId);

        // Request for DHCPOFFER, DHCPACK
        TrafficSelector.Builder dhcpServer =  DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPProtocol(IPv4.PROTOCOL_UDP)
        .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(dhcpServer.build(), PacketPriority.REACTIVE, appId);
    }

    /* Cancel request packet */
    private void cancelRequestPacket() {
		TrafficSelector.Builder dhcpClient =  DefaultTrafficSelector.builder()
        .matchEthType(Ethernet.TYPE_IPV4)
        .matchIPProtocol(IPv4.PROTOCOL_UDP)
        .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.cancelPackets(dhcpClient.build(), PacketPriority.REACTIVE, appId);
        
        TrafficSelector.Builder dhcpServer =  DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4)
            .matchIPProtocol(IPv4.PROTOCOL_UDP)
            .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
            .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.cancelPackets(dhcpServer.build(), PacketPriority.REACTIVE, appId);
    }

    private void cleanIntents() {
        Iterator<Intent> intents_iter = intentService.getIntents().iterator();

        while(intents_iter.hasNext()) {
            intentService.purge(intents_iter.next());
        }
    }

    private void submitIntent(PointToPointIntent intent) {
        FilteredConnectPoint ingress = intent.filteredIngressPoint();
        FilteredConnectPoint egress = intent.filteredEgressPoint();

        log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
            ingress.connectPoint().deviceId(),
            ingress.connectPoint().port(),
            egress.connectPoint().deviceId(),
            egress.connectPoint().port());

        intentService.submit(intent);
    }

    private Key getKey(ElementId src, ElementId dst) {
        String src_id_str = src.toString();
        String dst_id_str = dst.toString();
        Key key;

        if (src_id_str.compareTo(dst_id_str) < 0) {
            key = Key.of(src_id_str + dst_id_str, appId);
        } else {
            key = Key.of(dst_id_str + src_id_str, appId);
        }

        return key;
    }

    private List<Link> getLinks(ElementId src, ElementId dst) {
        Set<Path> paths = pathService.getPaths(src, dst);
        Iterator<Path> paths_iter = paths.iterator();
        List<Link> links = paths_iter.next().links();

        return links;
    }

    private PointToPointIntent buildP2PIntent(Ethernet ethPacket, Key key, List<Link> links, FilteredConnectPoint ingress, FilteredConnectPoint egress) {
        TrafficSelector.Builder selector_builder = DefaultTrafficSelector.builder();
        PointToPointIntent.Builder p2p_intent_builder = PointToPointIntent.builder();

        // Set up selector
        selector_builder.matchEthSrc(ethPacket.getSourceMAC());
        selector_builder.matchEthDst(ethPacket.getDestinationMAC());
        selector_builder.matchEthType(Ethernet.TYPE_IPV4);
        selector_builder.matchIPProtocol(IPv4.PROTOCOL_UDP);
        selector_builder.matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        selector_builder.matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));

        // Set up P2P intent
        p2p_intent_builder.appId(appId);
        p2p_intent_builder.key(key);
        p2p_intent_builder.suggestedPath(links);
        p2p_intent_builder.filteredIngressPoint(ingress);
        p2p_intent_builder.filteredEgressPoint(egress);
        p2p_intent_builder.selector(selector_builder.build());
        p2p_intent_builder.treatment(DefaultTrafficTreatment.emptyTreatment());
        p2p_intent_builder.priority(50000);
        
        return p2p_intent_builder.build();
    }

    /* Send out the packet from the specified port */
    private void packetout(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /* Broadcast the packet */
    private void flood(PacketContext context) {
        packetout(context, PortNumber.FLOOD);
    }

    /* Handle the packets coming from switchs */
    private class MyPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPacket = pkt.parsed();
            ConnectPoint src_cp = pkt.receivedFrom();

            PortNumber output_port = null;

            if (ethPacket.getEtherType() == Ethernet.TYPE_ARP) {
                flood(context);
                return;
            }

            if (ethPacket.isBroadcast()) {
                /* Client to Server */
                HostId src_hostID = HostId.hostId(ethPacket.getSourceMAC());
                Key key = getKey(src_hostID, dhcp_server_cp.deviceId());
                PointToPointIntent p2p_intent = (PointToPointIntent) intentService.getIntent(key);
                List<Link> links = null;

                if (p2p_intent == null) {
                    /* No intent, Create an intent and sumbit it */
                    FilteredConnectPoint ingree_point = new FilteredConnectPoint(src_cp);
                    FilteredConnectPoint egress_point = new FilteredConnectPoint(dhcp_server_cp);

                    if (src_cp.deviceId().toString().compareTo(dhcp_server_cp.deviceId().toString()) != 0) {
                        links = getLinks(src_cp.deviceId(), dhcp_server_cp.deviceId());
                        output_port = links.get(0).src().port();
                    } else {
                        output_port = dhcp_server_cp.port();
                    }

                    p2p_intent = buildP2PIntent(ethPacket, key, links, ingree_point, egress_point);
                    submitIntent(p2p_intent);
                } else {
                    /* Intent is already exist */
                    FilteredConnectPoint egress_point = p2p_intent.filteredEgressPoint();

                    if (src_cp.deviceId().toString().compareTo(egress_point.connectPoint().deviceId().toString()) != 0) {
                        links = getLinks(src_cp.deviceId(), dhcp_server_cp.deviceId());
                        output_port = links.get(0).src().port();
                    } else {
                        output_port = egress_point.connectPoint().port();
                    }
                }
            } else {
                /* Server to Client */
                HostId src_hostID = HostId.hostId(ethPacket.getSourceMAC());
                HostId dst_hostID = HostId.hostId(ethPacket.getDestinationMAC());
                Host dst_host = hostService.getHost(dst_hostID);
                ConnectPoint dst_cp = new ConnectPoint(dst_host.location().elementId(), dst_host.location().port());  //log.info("S2C, dst cp: {}", dst_cp);

                Key key = getKey(src_hostID, dst_hostID);
                PointToPointIntent p2p_intent = (PointToPointIntent) intentService.getIntent(key);
                
                List<Link> links = null;

                if (p2p_intent == null) {
                    FilteredConnectPoint ingree_point = new FilteredConnectPoint(src_cp);
                    FilteredConnectPoint egress_point = new FilteredConnectPoint(dst_cp);

                    if (src_cp.deviceId().toString().compareTo(dst_cp.deviceId().toString()) != 0) {
                        links = getLinks(src_cp.deviceId(), dst_cp.deviceId());
                        output_port = links.get(0).src().port();
                    } else {
                        output_port = dst_cp.port();
                    }

                    p2p_intent = buildP2PIntent(ethPacket, key, links, ingree_point, egress_point);
                    submitIntent(p2p_intent);
                } else {
                    /* Intent is already exist */
                    FilteredConnectPoint egress_point = p2p_intent.filteredEgressPoint();
                    
                    if (src_cp.deviceId().toString().compareTo(egress_point.connectPoint().deviceId().toString()) != 0) {
                        links = getLinks(src_cp.deviceId(), dst_cp.deviceId());
                        output_port = links.get(0).src().port();
                    } else {
                        output_port = egress_point.connectPoint().port();
                    }
                }
            }
            
            packetout(context, output_port);
        }   
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) &&
                 event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = netCfgService.getConfig(appId, DhcpConfig.class);

                if (config != null) {
                    dhcp_server_cp = ConnectPoint.fromString(config.getServerLocation());
                    
                    log.info("DHCP server is connected to `{}`, port `{}`", dhcp_server_cp.deviceId(), dhcp_server_cp.port());
                }
            }
        }
    }
}
