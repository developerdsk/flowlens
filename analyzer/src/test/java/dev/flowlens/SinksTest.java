package dev.flowlens;

import dev.flowlens.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B11, E3: databases, outbound HTTP, messaging. */
class SinksTest {

    @TempDir Path dir;

    private static String endpoint(String path, String body) {
        return "@org.springframework.web.bind.annotation.PostMapping(\"" + path + "\") public void m" + Math.abs(path.hashCode()) + "() { " + body + " }\n";
    }

    @Test
    void tableNamesHonourSchemaAndName_B11() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Order.java", "package demo; @jakarta.persistence.Entity @jakarta.persistence.Table(schema = \"sales\", name = \"orders\") public class Order { @jakarta.persistence.Id Long id; }")
                .java("demo/OrderLine.java", "package demo; @jakarta.persistence.Entity public class OrderLine { @jakarta.persistence.Id Long id; }")
                .java("demo/OrderRepo.java", "package demo; public interface OrderRepo extends org.springframework.data.repository.CrudRepository<Order, Long> {}")
                .java("demo/LineRepo.java", "package demo; public interface LineRepo extends org.springframework.data.repository.CrudRepository<OrderLine, Long> {}")
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { OrderRepo o; LineRepo l;\n"
                        + endpoint("/o", "o.save(null);") + endpoint("/l", "l.deleteById(1L);") + "}")
                .analyze();
        assertEquals(List.of("UPSERT sales.orders"), r.flowOf("POST /o"));
        assertEquals(List.of("DELETE order_line"), r.flowOf("POST /l"));
    }

    @Test
    void jdbcJpaMongoJooqRedis() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Doc.java", "package demo; @org.springframework.data.mongodb.core.mapping.Document(\"docs\") public class Doc { String id; }")
                .java("demo/Acc.java", "package demo; @jakarta.persistence.Entity public class Acc { @jakarta.persistence.Id Long id; }")
                .java("demo/C.java", "package demo;\n"
                        + "import org.springframework.jdbc.core.JdbcTemplate;\n"
                        + "@org.springframework.web.bind.annotation.RestController public class C {\n"
                        + "  static final String INSERT_AUDIT = \"INSERT INTO audit_log(id, msg) VALUES (?, ?)\";\n"
                        + "  JdbcTemplate jdbc; jakarta.persistence.EntityManager em; org.springframework.data.mongodb.core.MongoTemplate mongo;\n"
                        + "  org.jooq.DSLContext dsl; org.springframework.data.redis.core.StringRedisTemplate redis;\n"
                        + "  org.springframework.jdbc.core.simple.JdbcClient client;\n"
                        + endpoint("/jdbc", "jdbc.update(INSERT_AUDIT, 1, \"x\"); String sql = \"select * from users where id = ?\"; jdbc.queryForObject(sql, String.class, 1);")
                        + endpoint("/client", "client.sql(\"UPDATE users SET name = :n\").param(\"n\", 1).update();")
                        + endpoint("/em", "em.persist(new Acc()); em.find(Acc.class, 1L); em.createQuery(\"delete from Acc a\").executeUpdate(); em.flush();")
                        + endpoint("/mongo", "mongo.insert(new Doc()); mongo.find(null, Doc.class);")
                        + endpoint("/jooq", "dsl.insertInto(Tables.BOOK).set(null, null).execute(); dsl.selectFrom(AUTHOR).fetch();")
                        + endpoint("/redis", "redis.opsForValue().set(\"session:\" + 1, \"v\"); redis.delete(\"cart:1\");")
                        + "}")
                .analyze();
        assertEquals(List.of("INSERT audit_log", "SELECT users"), r.flowOf("POST /jdbc"));
        assertEquals(List.of("UPDATE users"), r.flowOf("POST /client"));
        assertEquals(List.of("DELETE acc", "INSERT acc", "SELECT acc"), r.flowOf("POST /em"));
        assertEquals(List.of("INSERT docs", "SELECT docs"), r.flowOf("POST /mongo"));
        assertEquals(List.of("INSERT book", "SELECT author"), r.flowOf("POST /jooq"));
        assertEquals(List.of("DELETE redis:cart", "UPSERT redis:session"), r.flowOf("POST /redis"));
        assertEquals("jdbc", r.node("INSERT audit_log").tech);
    }

    @Test
    void outboundHttpAndMessaging() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/InventoryClient.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @org.springframework.cloud.openfeign.FeignClient(name = "inventory", path = "/api")
                        public interface InventoryClient { @GetMapping("/items/{id}") String item(@PathVariable Long id); }
                        """)
                .java("demo/C.java", "package demo;\n"
                        + "@org.springframework.web.bind.annotation.RestController public class C {\n"
                        + "  org.springframework.web.client.RestTemplate rest; org.springframework.web.reactive.function.client.WebClient web;\n"
                        + "  org.springframework.kafka.core.KafkaTemplate<String, String> kafka; org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;\n"
                        + "  org.springframework.jms.core.JmsTemplate jms; org.springframework.cloud.stream.function.StreamBridge bridge; InventoryClient inventory;\n"
                        + endpoint("/http", "rest.getForObject(\"http://pricing/prices/{id}\", String.class, 1); web.post().uri(\"/notify\").retrieve().toBodilessEntity(); inventory.item(1L);")
                        + endpoint("/msg", "kafka.send(\"orders\", \"k\", \"v\"); rabbit.convertAndSend(\"ex\", \"rk\", \"m\"); jms.convertAndSend(\"audit\", \"m\"); bridge.send(\"out-0\", \"m\");")
                        + "}")
                .analyze();
        assertEquals(List.of("GET http://pricing/prices/{id}", "GET inventory/api/items/{id}", "POST /notify"), r.flowOf("POST /http"));
        assertEquals(List.of("JMS → audit", "KAFKA → orders", "RABBIT → ex/rk", "STREAM → out-0"), r.flowOf("POST /msg"));
        Node kafka = r.node("KAFKA → orders");
        assertEquals("external", kafka.type);
        assertEquals("kafka", kafka.tech);
    }

    @Test
    void myBatisXmlAndAnnotations() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/UserMapper.java", """
                        package demo;
                        @org.apache.ibatis.annotations.Mapper
                        public interface UserMapper {
                            int insertUser(Object u);
                            int updateUser(Object u);
                            @org.apache.ibatis.annotations.Delete("DELETE FROM users WHERE id = #{id}") int remove(Long id);
                        }
                        """)
                .file("src/main/resources/mapper/UserMapper.xml", """
                        <?xml version="1.0" encoding="UTF-8" ?>
                        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                        <mapper namespace="demo.UserMapper">
                          <sql id="cols">id, name</sql>
                          <insert id="insertUser">INSERT INTO users (<include refid="cols"/>) VALUES (#{id}, #{name})</insert>
                          <update id="updateUser">update users <set><if test="name != null">name = #{name},</if></set> where id = #{id}</update>
                          <update id="touch">update <trim prefix="" suffix="">users_audit</trim> <set>at = now()</set></update>
                        </mapper>
                        """)
                .java("demo/C.java", "package demo; @org.springframework.web.bind.annotation.RestController public class C { UserMapper m;\n"
                        + endpoint("/u", "m.insertUser(null); m.remove(1L); m.updateUser(null);") + "}")
                .analyze();
        assertEquals(List.of("DELETE users", "INSERT users", "UPDATE users"), r.flowOf("POST /u"));
        assertEquals("mybatis", r.node("INSERT users").tech);
    }

    @Test
    void sqlParsing() {
        assertEquals(new SinkClassifier.Sink("INSERT", "audit"), SinkClassifier.parseQuery("insert into audit select * from x", null));
        assertEquals(new SinkClassifier.Sink("UPDATE", "account"), SinkClassifier.parseQuery("update Account a set a.status = :s", null));
        assertEquals(new SinkClassifier.Sink("SELECT", "orders"), SinkClassifier.parseQuery("WITH t AS (select 1 from dual) SELECT * FROM orders", null));
        assertEquals(new SinkClassifier.Sink("UPSERT", "kv"), SinkClassifier.parseQuery("MERGE INTO kv USING ...", null));
        assertEquals(new SinkClassifier.Sink("SELECT", "account"), SinkClassifier.parseQuery("from Account where id = 1", null));
        assertEquals(new SinkClassifier.Sink("DELETE", "sales.orders"), SinkClassifier.parseQuery("/* c */ DELETE FROM `sales`.`orders` WHERE id=1", null));
        assertNull(SinkClassifier.parseQuery("   ", null));
    }
}
