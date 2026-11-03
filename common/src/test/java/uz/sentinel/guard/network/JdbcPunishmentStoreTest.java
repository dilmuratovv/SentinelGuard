package uz.sentinel.guard.network;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class JdbcPunishmentStoreTest {
  JdbcDataSource source() {
    var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"); return ds;
  }
  NetworkPunishment punishment(UUID player) {
    return new NetworkPunishment(UUID.randomUUID(),player,NetworkPunishment.Type.BAN,"test reason","azicoo","survival",Instant.ofEpochMilli(1000),Instant.ofEpochMilli(2000),false);
  }
  @Test void otherServersSeeCommittedPunishmentAndExpiry() throws Exception {
    var ds=source(); var writer=new JdbcPunishmentStore(ds);writer.initialize();
    var reader=new JdbcPunishmentStore(ds);var p=punishment(UUID.randomUUID());writer.create(p);
    assertEquals(1,reader.active(p.player(),Instant.ofEpochMilli(1000)).size());
    assertTrue(reader.active(p.player(),Instant.ofEpochMilli(2000)).isEmpty());
    assertTrue(reader.active(UUID.randomUUID(),Instant.ofEpochMilli(1500)).isEmpty());
    try(var c=ds.getConnection();var q=c.createStatement();var rows=q.executeQuery("SELECT COUNT(*) FROM sg_outbox")){rows.next();assertEquals(1,rows.getInt(1));}
  }
  @Test void outboxFailureRollsBackPunishment() throws Exception {
    var ds=source(); var store=new JdbcPunishmentStore(ds);store.initialize();
    try(var c=ds.getConnection();var q=c.createStatement()){q.execute("DROP TABLE sg_outbox");}
    var p=punishment(UUID.randomUUID()); assertThrows(SQLException.class,()->store.create(p));
    assertTrue(store.active(p.player(),Instant.ofEpochMilli(1500)).isEmpty());
  }
  @Test void duplicateIdCannotCreateDuplicateOutbox() throws Exception {
    var ds=source();var store=new JdbcPunishmentStore(ds);store.initialize();var p=punishment(UUID.randomUUID());
    store.create(p);assertThrows(SQLException.class,()->store.create(p));
    assertEquals(1,store.active(p.player(),Instant.ofEpochMilli(1500)).size());
    try(var c=ds.getConnection();var q=c.createStatement();var rows=q.executeQuery("SELECT COUNT(*) FROM sg_outbox")){rows.next();assertEquals(1,rows.getInt(1));}
  }
}
