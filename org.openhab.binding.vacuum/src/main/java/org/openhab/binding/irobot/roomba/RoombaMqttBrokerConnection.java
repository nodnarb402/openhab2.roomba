package org.openhab.binding.irobot.roomba;

import org.eclipse.smarthome.io.transport.mqtt.MqttBrokerConnection;
import org.openhab.binding.irobot.internal.RawMQTT;

public class RoombaMqttBrokerConnection extends MqttBrokerConnection {

    public RoombaMqttBrokerConnection(String host, String clientId) {
        super(host, RawMQTT.ROOMBA_MQTT_PORT, true, clientId);
    }

    // !!! GROSS HACK !!!
    // MqttBrokerConnection tries to unsubscribe from everything that has been
    // subscribed to, but Roomba just ignores unsubscribe requests and never replies,
    // so that normal stop() would simply hang and never complete. Fortunately a lot
    // of MqttBrokerConnection guts are protected. so we can substitute stop() function
    // with our own code.
    public void forceStop() {
        // Abort a connection attempt
        isConnecting = false;

        // Cancel the timeout future. If stop is called we can safely assume there is no interest in a connection
        // anymore.
        cancelTimeoutFuture();

        // Stop the reconnect strategy
        if (reconnectStrategy != null) {
            reconnectStrategy.stop();
        }

        // unsubscribeAll() would do this, avoid memory leaks
        subscribers.clear();

        // This will terminate the connection by calling client.disconnect()
        finalizeStopAfterDisconnect(true);
    }
}
