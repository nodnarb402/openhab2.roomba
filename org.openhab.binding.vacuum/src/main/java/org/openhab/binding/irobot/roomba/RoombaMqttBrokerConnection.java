// A very simple wrapper aroung HiveMQ, modeled after OpenHAB's own wrapper.
// Only enough for our purposes.
// Unfortunately we can't just reuse OpenHAB's code because it sends UNSUBSCRIBE
// request before disconnecting and Roomba doesn't like it. It just swallows the
// request and never sends any response, so stop() method never completes.

package org.openhab.binding.irobot.roomba;

import java.security.KeyStore;

import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;

import org.eclipse.jdt.annotation.Nullable;
import org.jetbrains.annotations.NotNull;
import org.openhab.binding.irobot.handler.RoombaHandler;
import org.openhab.binding.irobot.internal.RawMQTT;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedListener;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import io.netty.handler.ssl.util.SimpleTrustManagerFactory;

public class RoombaMqttBrokerConnection implements MqttClientDisconnectedListener {

    private Mqtt3AsyncClient client;
    private RoombaHandler handler;
    private Boolean isConnected = false;

    public static class RoombaTrustManagerFactory extends SimpleTrustManagerFactory {
        @Override
        protected void engineInit(@Nullable KeyStore keyStore) throws Exception {
        }

        @Override
        protected void engineInit(@Nullable ManagerFactoryParameters managerFactoryParameters) throws Exception {
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return RawMQTT.getTrustManagers();
        }
    };

    private static RoombaTrustManagerFactory roombaTMFactory = new RoombaTrustManagerFactory();

    public RoombaMqttBrokerConnection(String host, String clientId, RoombaHandler owner) {
        handler = owner;
        client = MqttClient.builder().useMqttVersion3().serverHost(host).serverPort(RawMQTT.ROOMBA_MQTT_PORT)
                .identifier(clientId).addDisconnectedListener(this).sslWithDefaultConfig().sslConfig()
                .trustManagerFactory(roombaTMFactory).applySslConfig().buildAsync();
    }

    public void start(String username, String password) {
        isConnected = true;
        client.connectWith().simpleAuth().username(username).password(password.getBytes()).applySimpleAuth().send()
                .whenComplete((connAck, connectError) -> {
                    // We are not handling connection errors here because
                    // onDisconnected gets called anyway
                    if (connectError == null) {
                        client.subscribeWith().topicFilter("#").callback(publish -> {
                            handler.processMessage(publish.getTopic().toString(), publish.getPayloadAsBytes());
                        }).send().whenComplete((subAck, subError) -> {
                            handler.onConnected();
                        });
                    }
                });
    }

    public void stop() {
        isConnected = false;
        client.disconnect();
    }

    public void publish(String topic, byte[] data) {
        // Only this qos value is accepted by Roomba, others just cause it
        // to reject the command and drop the connection.
        client.publishWith().topic(topic).payload(data).qos(MqttQos.AT_MOST_ONCE).send();
    }

    @Override
    public void onDisconnected(@NotNull MqttClientDisconnectedContext context) {
        if (isConnected) {
            isConnected = false;
            handler.onDisconnected(context.getCause());
        }
    }

}
