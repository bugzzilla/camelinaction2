package camelinaction;

import javax.sql.DataSource;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.test.spring.CamelSpringTestSupport;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public class XARollbackBeforeDbTest extends CamelSpringTestSupport {

    private JdbcTemplate jdbc;

    @Before
    public void setupDatabase() throws Exception {
        DataSource ds = context.getRegistry().lookupByNameAndType("myDataSource", DataSource.class);
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("create table partner_metric "
            + "( partner_id varchar(10), time_occurred varchar(20), status_code varchar(3), perf_time varchar(10) )");
    }

    @After
    public void dropDatabase() throws Exception {
        if (jdbc != null) {
            jdbc.execute("drop table partner_metric");
        }
    }

    @Override
    protected AbstractApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext("spring-context.xml");
    }

    @Test
    public void testXaRollbackBeforeDb() throws Exception {
        // there should be 0 row in the database when we start
        int rows = jdbc.queryForObject("select count(*) from partner_metric", Integer.class);
        assertEquals(0, rows);

        // partner id as 0 will cause rollback
        String xml = "<?xml version=\"1.0\"?><partner id=\"0\"><date>201503180816</date><code>200</code><time>4387</time></partner>";
        template.sendBody("activemq:queue:partners", xml);

        // wait for the route to complete with failure
        Thread.sleep(15000);

        // data not inserted so there should be 0 rows
        rows = jdbc.queryForObject("select count(*) from partner_metric", Integer.class);
        assertEquals(0, rows);

        // should be in DLQ
        // now check that the message is on the queue by consuming it again
        String dlq = consumer.receiveBody("activemq:queue:ActiveMQ.DLQ", 2000, String.class);
        assertNotNull("Should not lose message", dlq);
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                PropertiesComponent pc = context.getComponent("properties", PropertiesComponent.class);
                pc.setLocation("camelinaction/sql.properties");

                from("activemq:queue:partners")
                    .transacted()
                    .log("*** transacted ***")
                    .bean(PartnerServiceBean.class, "toMap")
                    .log("*** before SQL ***")
                    .throwException(new IllegalArgumentException("Forced failure before DB"))
                    .to("sql:{{sql-insert}}?dataSource=#myDataSource")
                    .log("*** after SQL ***");
            }
        };
    }

}
