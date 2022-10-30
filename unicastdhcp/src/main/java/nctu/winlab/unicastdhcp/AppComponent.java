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
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.FilteredConnectPoint;
// import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.Path;
import org.onosproject.net.Host;
// import org.onosproject.net.HostId;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
// import org.onosproject.net.flow.TrafficTreatment;
// import org.onosproject.net.packet.InboundPacket;
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
import org.onosproject.net.topology.Topology;
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
            intentService.withdraw(intents_iter.next());
        }
    }

    private Key getKey(ConnectPoint src, ConnectPoint dst) {
        String src_id_str = src.deviceId().toString();
        String dst_id_str = dst.deviceId().toString();
        Key key;

        if (src_id_str.compareTo(dst_id_str) < 0) {
            key = Key.of(src_id_str + dst_id_str, appId);
        } else {
            key = Key.of(dst_id_str + src_id_str, appId);
        }

        return key;
    }
    
    /* Send out the packet from the specified port */
    private void packetout(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    /* Handle the packets coming from switchs */
    private class MyPacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPacket = pkt.parsed();  log.info("eth: {}", ethPacket);
            ConnectPoint src_cp = pkt.receivedFrom();

            if (ethPacket.isBroadcast()) {
                /* Client to Server */
                Key key = getKey(src_cp, dhcp_server_cp);
                PointToPointIntent p2p_intent = (PointToPointIntent) intentService.getIntent(key);

                log.info("Get intent: {}", p2p_intent);

                if (p2p_intent == null) {
                    /* No intent, Create an intent and sumbit it */
                    TrafficSelector.Builder selector_builder = DefaultTrafficSelector.builder();
                    PointToPointIntent.Builder p2p_intent_builder = PointToPointIntent.builder();
                    FilteredConnectPoint ingree_point = new FilteredConnectPoint(src_cp);
                    FilteredConnectPoint egree_point = new FilteredConnectPoint(dhcp_server_cp);
                    Set<Path> paths = pathService.getPaths(src_cp.deviceId(), dhcp_server_cp.deviceId());

                    if (paths.size() == 0) {
                        log.warn("Paths are empty, waiting for path service to retrieve path information...");
                        return;
                    }

                    Iterator<Path> paths_iter = paths.iterator();
                    List<Link> links = paths_iter.next().links();
                    PortNumber output_port = links.get(0).src().port();

                    // log.info("Links: {}", links);

                    selector_builder.matchEthType(Ethernet.TYPE_IPV4);
                    selector_builder.matchIPProtocol(IPv4.PROTOCOL_UDP);
                    selector_builder.matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
                    selector_builder.matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));

                    p2p_intent_builder.appId(appId);
                    p2p_intent_builder.key(key);
                    p2p_intent_builder.suggestedPath(links);
                    p2p_intent_builder.filteredIngressPoint(ingree_point);
                    p2p_intent_builder.filteredEgressPoint(egree_point);
                    p2p_intent_builder.selector(selector_builder.build());
                    p2p_intent_builder.treatment(DefaultTrafficTreatment.emptyTreatment());
                    p2p_intent_builder.priority(50000);
                    p2p_intent = p2p_intent_builder.build();

                    // log.info("p2p_intent: {}", p2p_intent);
                    log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                        ingree_point.connectPoint().deviceId(),
                        ingree_point.connectPoint().port(),
                        egree_point.connectPoint().deviceId(),
                        egree_point.connectPoint().port());

                    intentService.submit(p2p_intent);
                    packetout(context, output_port);
                } else {
                    /* Intent is already exist */
                    FilteredConnectPoint ingree_point = p2p_intent.filteredIngressPoint();
                    FilteredConnectPoint egree_point = p2p_intent.filteredEgressPoint();

                    log.info("Intent `{}`, port `{}` => `{}`, port `{}` is submitted.",
                        ingree_point.connectPoint().deviceId(),
                        ingree_point.connectPoint().port(),
                        egree_point.connectPoint().deviceId(),
                        egree_point.connectPoint().port());

                    intentService.submit(p2p_intent);
                }

            } else {
                /* Server to Client */
            }


        }   
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED) &&
                 event.configClass().equals(DhcpConfig.class)) {
                DhcpConfig config = netCfgService.getConfig(appId, DhcpConfig.class);

                if (config != null) {
                    String location = config.getServerLocation();
                    dhcp_server_cp = ConnectPoint.fromString(location);
                    log.info("DHCP Server location is {}", location);

                }
            }
        }
    }
}
