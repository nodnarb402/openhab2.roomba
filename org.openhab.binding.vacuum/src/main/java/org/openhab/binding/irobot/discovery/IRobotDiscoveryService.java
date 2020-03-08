package org.openhab.binding.irobot.discovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.net.NetUtil;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.openhab.binding.irobot.IRobotBindingConstants;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = DiscoveryService.class)
public class IRobotDiscoveryService extends AbstractDiscoveryService {

    private final static Logger logger = LoggerFactory.getLogger(IRobotDiscoveryService.class);
    private static final String UDP_PACKET_CONTENTS = "irobotmcs";
    private static final int REMOTE_UDP_PORT = 5678;

    private final Runnable scanner;

    public IRobotDiscoveryService() {
        super(Collections.singleton(IRobotBindingConstants.THING_TYPE_ROOMBA), 30, true);
        scanner = createScanner();
    }

    @Override
    protected void startScan() {
        logger.trace("startScan");
        scheduler.execute(scanner);
    }

    private Runnable createScanner() {
        return () -> {
            long timestampOfLastScan = getTimestampOfLastScan();
            for (InetAddress broadcastAddress : getBroadcastAddresses()) {
                logger.debug("Starting broadcast for {}", broadcastAddress.toString());

                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    socket.setReuseAddress(true);
                    byte[] packetContents = UDP_PACKET_CONTENTS.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket packet = new DatagramPacket(packetContents, packetContents.length, broadcastAddress,
                            REMOTE_UDP_PORT);

                    // Send before listening in case the port isn't bound until here.
                    socket.send(packet);

                    // receivePacketAndDiscover will return false if no packet is received after 1 second
                    while (receivePacketAndDiscover(socket)) {
                    }
                } catch (Exception e) {
                    // Nothing to do here - the host couldn't be found, likely because it doesn't exist
                }
            }

            removeOlderResults(timestampOfLastScan);
        };
    }

    private List<InetAddress> getBroadcastAddresses() {
        ArrayList<InetAddress> addresses = new ArrayList<>();

        for (String broadcastAddress : NetUtil.getAllBroadcastAddresses()) {
            try {
                addresses.add(InetAddress.getByName(broadcastAddress));
            } catch (UnknownHostException e) {
                logger.warn("Error broadcasting to {}", broadcastAddress, e);
            }
        }

        return addresses;
    }

    private boolean receivePacketAndDiscover(DatagramSocket socket) {
        byte[] buffer = new byte[1024];
        DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);

        try {
            socket.setSoTimeout(1000 /* one second */);
            socket.receive(incomingPacket);
        } catch (Exception e) {
            // This is not really an error, eventually we get a timeout
            // due to a loop in the caller
            return false;
        }

        String host = incomingPacket.getAddress().toString().substring(1);

        /*
         * incomingPacket is a JSON of the following contents (addresses are undisclosed):
         * @formatter:off
         * {
         *   "ver":"3",
         *   "hostname":"Roomba-3168820480607740",
         *   "robotname":"Roomba",
         *   "ip":"XXX.XXX.XXX.XXX",
         *   "mac":"XX:XX:XX:XX:XX:XX",
         *   "sw":"v2.4.6-3",
         *   "sku":"R981040",
         *   "nc":0,
         *   "proto":"mqtt",
         *   "cap":{
         *     "pose":1,
         *     "ota":2,
         *     "multiPass":2,
         *     "carpetBoost":1,
         *     "pp":1,
         *     "binFullDetect":1,
         *     "langOta":1,
         *     "maps":1,
         *     "edge":1,
         *     "eco":1,
         *     "svcConf":1
         *   }
         * }
         * @formatter:on
         */
        String reply = new String(incomingPacket.getData());

        logger.debug("Received reply from {}:", host);
        logger.debug(reply);

        try {
            JSONObject irobotInfo = new JSONObject(reply);
            int version = irobotInfo.getInt("ver");

            // Checks below come from Roomba980-Python
            if (version < 2) {
                logger.debug("Found unsupported iRobot \"{}\" version {} at {}", irobotInfo.getString("robotname"),
                        version, host);
                return true;
            }

            String[] hostname = irobotInfo.getString("hostname").split("-");

            // This also comes from Roomba980-Python. Comments there say that "iRobot"
            // prefix is used by i7. We assume for other robots it would be product
            // name, e. g. "Scooba"
            if ((hostname[0].equals("Roomba") || hostname[0].equals("iRobot"))) {
                ThingUID thingUID = new ThingUID(IRobotBindingConstants.THING_TYPE_ROOMBA, host.replace('.', '_'));
                DiscoveryResult result = DiscoveryResultBuilder.create(thingUID)
                        .withProperty(RoombaConfiguration.FIELD_IPADDRESS, host)
                        .withLabel("iRobot " + irobotInfo.getString("robotname")).build();

                thingDiscovered(result);
            }
        } catch (JSONException e) {
            logger.debug("Malformed JSON reply!");
            return true;
        }

        return true;
    }
}
