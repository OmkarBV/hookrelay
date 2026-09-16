package io.hookrelay.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.hookrelay.common.delivery.DeliveryTaskMessage;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Uses a real Testcontainers Kafka broker (not a mock) because dead-letter
 * routing is entirely about the container/error-handler machinery — a
 * malformed record has to fail during Consumer.poll()'s own deserialization,
 * which nothing short of a real broker + real listener container exercises.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeadLetterRoutingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("hookrelay.security.secret-encryption-key", () -> "2P0OfgmRP7wHAAILerwZoJCC92TIw0RjZ4l0kXhIT/k=");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Real broker this time, so the listener container really runs.
        registry.add("hookrelay.dispatcher.scheduling.enabled", () -> "false");
    }

    @Autowired
    private KafkaTemplate<String, DeliveryTaskMessage> kafkaTemplate;

    private KafkaConsumer<String, byte[]> newDltConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList("webhook.deliveries.DLT"));
        return consumer;
    }

    @Test
    void aDeliveryReferencingNothingThatExistsIsDeadLettered() {
        UUID nonExistentDeliveryId = UUID.randomUUID();
        UUID someEndpointId = UUID.randomUUID();

        try (KafkaConsumer<String, byte[]> dltConsumer = newDltConsumer()) {
            kafkaTemplate.send("webhook.deliveries", someEndpointId.toString(),
                    new DeliveryTaskMessage(nonExistentDeliveryId));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, byte[]> records = dltConsumer.poll(Duration.ofSeconds(2));
                assertThat(records.count()).isGreaterThan(0);
                ConsumerRecord<String, byte[]> record = records.iterator().next();
                assertThat(new String(record.value())).contains(nonExistentDeliveryId.toString());
            });
        }
    }

    @Test
    void aMalformedMessageIsDeadLettered() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (KafkaProducer<String, String> rawProducer = new KafkaProducer<>(producerProps);
             KafkaConsumer<String, byte[]> dltConsumer = newDltConsumer()) {
            rawProducer.send(new ProducerRecord<>("webhook.deliveries", "some-key", "not valid json at all"));
            rawProducer.flush();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, byte[]> records = dltConsumer.poll(Duration.ofSeconds(2));
                assertThat(records.count()).isGreaterThan(0);
            });
        }
    }
}
