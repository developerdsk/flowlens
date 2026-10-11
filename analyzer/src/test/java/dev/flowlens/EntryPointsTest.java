package dev.flowlens;

import dev.flowlens.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B5, B13, E2: entry point detection across frameworks. */
class EntryPointsTest {

    @TempDir Path dir;

    @Test
    void producesIsNotThePath_andArraysAndConstantsResolve_B5() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Paths.java", "package demo; public final class Paths { public static final String API = \"/api\"; public static final String USERS = API + \"/users\"; }")
                .java("demo/C.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        import static demo.Paths.API;
                        @RestController
                        @RequestMapping(value = API, produces = "application/json")
                        public class C {
                            @GetMapping(produces = "application/json") public String root() { return ""; }
                            @GetMapping({"/a", "/b"}) public String two() { return ""; }
                            @PostMapping(path = Paths.USERS + "/{id}", consumes = "application/json") public void users() {}
                            @RequestMapping(value = "/m", method = {RequestMethod.PUT, RequestMethod.PATCH}) public void m() {}
                        }
                        """)
                .analyze();
        assertEquals(List.of("GET /api", "GET /api/a", "GET /api/b", "PATCH /api/m", "POST /api/api/users/{id}", "PUT /api/m"), r.https());
    }

    @Test
    void overloadedHandlersGetDistinctIds_B13() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/C.java", """
                        package demo;
                        import org.springframework.web.bind.annotation.*;
                        @RestController public class C {
                            @GetMapping("/x") public String find(@RequestParam String a) { return a; }
                            @GetMapping(value = "/x", params = "b") public String find(@RequestParam Integer b) { return ""; }
                        }
                        """)
                .analyze();
        assertEquals(2, r.endpoints().size());
        assertNotEquals(r.endpoints().get(0).id, r.endpoints().get(1).id);
    }

    @Test
    void jaxRsAndMicronaut_E2() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/R.java", """
                        package demo;
                        import jakarta.ws.rs.*;
                        @Path("/items")
                        public class R {
                            @GET public String list() { return ""; }
                            @POST @Path("/{id}") public void create(Item item) {}
                        }
                        """)
                .java("demo/Item.java", "package demo; public class Item { String name; }")
                .java("mn/M.java", """
                        package mn;
                        import io.micronaut.http.annotation.*;
                        @Controller("/hello")
                        public class M { @Get("/{name}") public String hi(String name) { return name; } }
                        """)
                .analyze();
        assertEquals(List.of("GET /hello/{name}", "GET /items", "POST /items/{id}"), r.https());
        assertEquals("jaxrs", r.endpoint("GET /items").framework);
        assertEquals("micronaut", r.endpoint("GET /hello/{name}").framework);
    }

    @Test
    void messagingAndScheduledEntryPoints_E2() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/L.java", """
                        package demo;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class L {
                            static final String TOPIC = "orders";
                            @org.springframework.kafka.annotation.KafkaListener(topics = TOPIC, groupId = "g") public void onOrder(String m) {}
                            @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "mails") public void onMail(String m) {}
                            @org.springframework.jms.annotation.JmsListener(destination = "audit") public void onAudit(String m) {}
                            @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * *") public void hourly() {}
                            @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5000) public void often() {}
                        }
                        """)
                .analyze();
        assertEquals(List.of("JMS audit", "KAFKA orders", "RABBIT mails", "SCHEDULED cron=0 0 * * * *", "SCHEDULED fixedRate=5000"), r.https());
        assertEquals("message", r.endpoint("KAFKA orders").kind);
        assertEquals("scheduled", r.endpoint("SCHEDULED fixedRate=5000").kind);
    }

    @Test
    void webFluxRouterFunctions_E2() throws Exception {
        TestProject.Result r = new TestProject(dir)
                .java("demo/Routes.java", """
                        package demo;
                        import org.springframework.context.annotation.*;
                        import org.springframework.web.reactive.function.server.*;
                        import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                        import static org.springframework.web.reactive.function.server.RouterFunctions.route;
                        @Configuration
                        public class Routes {
                            @Bean RouterFunction<ServerResponse> r1(PetHandler h) {
                                return route().path("/pets", b -> b.GET("/{id}", h::get).POST("", h::create)).build();
                            }
                            @Bean RouterFunction<ServerResponse> r2(PetHandler h) {
                                return route(GET("/health"), req -> ServerResponse.ok().build());
                            }
                        }
                        """)
                .java("demo/PetHandler.java", """
                        package demo;
                        @org.springframework.stereotype.Component
                        public class PetHandler {
                            public Object get(Object req) { return null; }
                            public Object create(Object req) { return null; }
                        }
                        """)
                .analyze();
        assertEquals(List.of("GET /health", "GET /pets/{id}", "POST /pets"), r.https());
        Node get = r.endpoint("GET /pets/{id}");
        assertEquals("PetHandler.get()", get.label);
    }
}
