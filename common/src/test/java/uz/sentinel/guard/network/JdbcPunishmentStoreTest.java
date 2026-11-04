package uz.sentinel.guard.network;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
/** Runs on H2 (MySQL mode) by default; set SG_TEST_JDBC_URL/USER/PASSWORD to run against MariaDB. */
class JdbcPunishmentStoreTest {
  static final Instant T0 = Instant.ofEpochMilli(1000), T1 = Instant.ofEpochMilli(2000);
  DataSource source() throws SQLException {
    String url = System.getenv("SG_TEST_JDBC_URL");
    if (url == null || url.isBlank()) {
      var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"); return ds;
    }
    DataSource ds = new DriverManagerSource(url, System.getenv("SG_TEST_JDBC_USER"), System.getenv("SG_TEST_JDBC_PASSWORD"));
    try (var c = ds.getConnection(); var s = c.createStatement()) {
      for (String table : new String[]{"sg_outbox", "sg_punishments", "sg_schema", "sg_players", "sg_player_ips", "sg_sus", "sg_sus_log"}) s.execute("DROP TABLE IF EXISTS " + table);
    }
    return ds;
  }
  JdbcPunishmentStore store(DataSource ds) throws SQLException { var s = new JdbcPunishmentStore(ds); s.initialize(); return s; }
  static NetworkPunishment of(NetworkPunishment.Type type, UUID player, String ip, Instant start, Instant end) {
    return new NetworkPunishment(UUID.randomUUID(), player, "Steve", ip, type, "test reason", null, "azicoo", "survival", start, end, false, null);
  }
  static NetworkPunishment ban(UUID player) { return of(NetworkPunishment.Type.BAN, player, null, T0, T1); }
  static NetworkPunishment.Revocation by(String who) { return new NetworkPunishment.Revocation(who, "appeal", T0.plusMillis(10)); }
  long outboxCount(DataSource ds) throws SQLException {
    try (var c = ds.getConnection(); var q = c.createStatement(); var rows = q.executeQuery("SELECT COUNT(*) FROM sg_outbox")) { rows.next(); return rows.getLong(1); }
  }

  @Test void otherServersSeeCommittedPunishmentAndExpiry() throws Exception {
    var ds = source(); var writer = store(ds); var reader = new JdbcPunishmentStore(ds);
    var p = ban(UUID.randomUUID()); writer.create(p);
    assertEquals(1, reader.active(p.player(), T0).size());
    assertTrue(reader.active(p.player(), T1).isEmpty());
    assertTrue(reader.active(UUID.randomUUID(), Instant.ofEpochMilli(1500)).isEmpty());
    assertEquals(1, outboxCount(ds));
  }
  @Test void outboxFailureRollsBackPunishment() throws Exception {
    var ds = source(); var store = store(ds);
    try (var c = ds.getConnection(); var q = c.createStatement()) { q.execute("DROP TABLE sg_outbox"); }
    var p = ban(UUID.randomUUID()); assertThrows(SQLException.class, () -> store.create(p));
    assertTrue(store.active(p.player(), Instant.ofEpochMilli(1500)).isEmpty());
  }
  @Test void duplicateIdCannotCreateDuplicateOutbox() throws Exception {
    var ds = source(); var store = store(ds); var p = ban(UUID.randomUUID());
    store.create(p); assertThrows(SQLException.class, () -> store.create(p));
    assertEquals(1, store.active(p.player(), Instant.ofEpochMilli(1500)).size());
    assertEquals(1, outboxCount(ds));
  }
  @Test void newBanReplacesOldBanAtomically() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    var first = ban(player); store.create(first);
    var second = of(NetworkPunishment.Type.BAN, player, null, T0.plusMillis(5), null);
    assertEquals(List.of(first.id()), store.create(second));
    var active = store.active(player, T0.plusMillis(6));
    assertEquals(1, active.size()); assertEquals(second.id(), active.get(0).id());
    assertTrue(store.find(first.id()).orElseThrow().revoked());
    assertEquals(3, outboxCount(ds));
  }
  @Test void revokeIsGlobalAndPublishesEvent() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    var mute = of(NetworkPunishment.Type.MUTE, player, null, T0, null); store.create(mute);
    long before = store.latestSequence();
    assertEquals(List.of(mute.id()), store.revokeActive(player, null, EnumSet.of(NetworkPunishment.Type.MUTE), by("admin")));
    assertTrue(new JdbcPunishmentStore(ds).active(player, T0.plusMillis(20)).isEmpty());
    var events = store.outboxAfter(before, 10);
    assertEquals(1, events.size()); assertEquals(OutboxEvent.Action.REVOKED, events.get(0).action()); assertEquals(mute.id(), events.get(0).punishmentId());
    var revoked = store.find(mute.id()).orElseThrow().revocation();
    assertEquals("admin", revoked.actor()); assertEquals("appeal", revoked.reason());
    assertFalse(store.revoke(mute.id(), by("admin")), "second revoke is a no-op");
  }
  @Test void ipBanDeniesOtherAccountsOnIpv6Address() throws Exception {
    var ds = source(); var store = store(ds); UUID owner = UUID.randomUUID(), alt = UUID.randomUUID();
    String ip = IpAddresses.normalize("[2001:DB8::1]:25565");
    store.create(of(NetworkPunishment.Type.IP_BAN, owner, ip, T0, null));
    assertEquals(1, store.activeBans(alt, "/2001:db8:0:0:0:0:0:1", T0.plusMillis(1)).size());
    assertTrue(store.activeBans(alt, "2001:db8::2", T0.plusMillis(1)).isEmpty());
    assertEquals(1, store.activeBans(owner, null, T0.plusMillis(1)).size());
    var raw = of(NetworkPunishment.Type.IP_BAN, null, IpAddresses.normalize("10.0.0.7"), T0, null); store.create(raw);
    assertEquals(1, store.revokeActive(null, "10.0.0.7:1234", EnumSet.of(NetworkPunishment.Type.IP_BAN), by("admin")).size());
    assertEquals(1, store.activeBans(alt, ip, T0.plusMillis(20)).size(), "unrelated IP ban stays");
  }
  @Test void plainBanOnSharedIpIsNotRevokedByIpUnban() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    var p = of(NetworkPunishment.Type.BAN, player, "10.0.0.8", T0, null); store.create(p);
    assertTrue(store.revokeActive(null, "10.0.0.8", EnumSet.of(NetworkPunishment.Type.BAN, NetworkPunishment.Type.IP_BAN), by("x")).isEmpty());
    assertEquals(1, store.active(player, T0.plusMillis(1)).size());
  }
  @Test void kicksAreHistoryOnly() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    store.create(of(NetworkPunishment.Type.KICK, player, null, T0, null));
    assertTrue(store.active(player, T0).isEmpty());
    assertEquals(1, store.history(player, 10).size());
    assertEquals(1, store.byActor("azicoo", 10).size());
  }
  @Test void batchedReconciliationFindsOnlyBannedPlayers() throws Exception {
    var ds = source(); var store = store(ds); List<UUID> online = new ArrayList<>();
    for (int i = 0; i < 1200; i++) online.add(UUID.randomUUID());
    store.create(ban(online.get(3))); store.create(ban(online.get(1100)));
    store.create(of(NetworkPunishment.Type.MUTE, online.get(5), null, T0, null));
    var bans = store.activeBans(online, T0.plusMillis(1));
    assertEquals(Set.of(online.get(3), online.get(1100)), bans.stream().map(NetworkPunishment::player).collect(java.util.stream.Collectors.toSet()));
  }
  @Test void legacyImportIsIdempotent() throws Exception {
    var ds = source(); var store = store(ds); var p = ban(UUID.randomUUID());
    assertTrue(store.importIfAbsent(p)); assertFalse(store.importIfAbsent(p));
    assertEquals(1, store.history(p.player(), 10).size()); assertEquals(1, outboxCount(ds));
  }
  @Test void outboxIsOrderedAndPaged() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    for (int i = 0; i < 5; i++) store.create(of(NetworkPunishment.Type.WARN, player, null, T0.plusMillis(i), null));
    var first = store.outboxAfter(0, 3); var rest = store.outboxAfter(first.get(2).sequence(), 10);
    assertEquals(3, first.size()); assertEquals(2, rest.size());
    assertTrue(first.get(0).sequence() < first.get(1).sequence());
    assertEquals(rest.get(1).sequence(), store.latestSequence());
  }
  @Test void snapshotSchemaIsMigrated() throws Exception {
    var ds = source();
    try (var c = ds.getConnection(); var s = c.createStatement()) {
      s.executeUpdate("CREATE TABLE sg_punishments (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL, reason VARCHAR(2048) NOT NULL, actor VARCHAR(128) NOT NULL, server_id VARCHAR(128) NOT NULL, created_ms BIGINT NOT NULL, expires_ms BIGINT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, INDEX sg_player_active (player_uuid, kind, revoked)) ENGINE=InnoDB");
      s.executeUpdate("CREATE TABLE sg_outbox (sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE, player_uuid VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, created_ms BIGINT NOT NULL) ENGINE=InnoDB");
      s.executeUpdate("INSERT INTO sg_punishments (id,player_uuid,kind,reason,actor,server_id,created_ms,expires_ms,revoked) VALUES ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "','BAN','old','azicoo','lobby',1000,NULL,FALSE)");
    }
    var store = store(ds); store.initialize();
    assertEquals(1, store.recent(10).size());
    store.create(of(NetworkPunishment.Type.IP_BAN, null, "10.1.1.1", T0, null));
    try (var c = ds.getConnection(); var s = c.createStatement(); var rs = s.executeQuery("SELECT version FROM sg_schema WHERE id=1")) { rs.next(); assertEquals(3, rs.getInt(1)); }
  }
  @Test void banStampedByAFastClockIsEnforcedImmediately() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    var future = of(NetworkPunishment.Type.BAN, player, null, T0.plusSeconds(30), null); store.create(future);
    assertEquals(1, store.active(player, T0).size(), "another server's clock being ahead must not delay the ban");
    assertEquals(1, store.activeBans(player, null, T0).size());
  }
  @Test void playersAreSharedAndOnlyChangesArePublished() throws Exception {
    var ds = source(); var a = store(ds); var b = new JdbcPunishmentStore(ds); UUID id = UUID.randomUUID();
    assertTrue(a.recordPlayer(id, "Steve", "10.0.0.1", T0));
    assertFalse(a.recordPlayer(id, "Steve", "10.0.0.1", T0.plusMillis(5)), "same name and address writes nothing");
    assertTrue(b.recordPlayer(id, "Steve", "[2001:DB8::5]:1234", T0.plusMillis(10)), "new address is a change");
    assertTrue(b.recordPlayer(id, "SteveRenamed", "10.0.0.1", T0.plusMillis(20)));
    var loaded = a.loadPlayers(); assertEquals(1, loaded.size());
    var p = loaded.get(0); assertEquals("SteveRenamed", p.name()); assertEquals("10.0.0.1", p.latestIp());
    assertEquals(Set.of("10.0.0.1", "2001:db8:0:0:0:0:0:5"), p.ips());
    assertEquals(3, a.outboxAfter(0, 10).size());
    assertEquals(OutboxEvent.Action.PLAYER, a.outboxAfter(0, 10).get(0).action());
    assertNull(a.outboxAfter(0, 10).get(0).punishmentId());
    assertEquals("SteveRenamed", a.player(id).orElseThrow().name());
  }
  @Test void playerImportNeverOverwrites() throws Exception {
    var ds = source(); var store = store(ds); UUID id = UUID.randomUUID();
    store.recordPlayer(id, "Current", "10.0.0.2", T0);
    assertTrue(store.importPlayer(new NetworkPlayer(id, "Old", "10.0.0.9", Set.of("10.0.0.9")), T1), "extra address is added");
    var p = store.player(id).orElseThrow(); assertEquals("Current", p.name()); assertEquals("10.0.0.2", p.latestIp());
    assertEquals(Set.of("10.0.0.2", "10.0.0.9"), p.ips());
    assertFalse(store.importPlayer(new NetworkPlayer(id, "Old", "10.0.0.9", Set.of("10.0.0.9")), T1));
  }
  @Test void susListIsSharedCountedAndClearable() throws Exception {
    var ds = source(); var a = store(ds); var b = new JdbcPunishmentStore(ds); UUID id = UUID.randomUUID();
    a.addSus(id, "Steve", "  KillAura ", "mod1", T0);
    var sus = b.addSus(id, "Steve", "killaura", "mod2", T0.plusMillis(1));
    assertTrue(sus.active()); assertEquals(2, sus.history().size());
    assertEquals(List.of("killaura", "killaura"), sus.history().stream().map(NetworkSus.Entry::reason).toList());
    assertEquals("mod2", sus.history().get(1).staff());
    assertTrue(a.removeSus(id, T1)); assertFalse(a.removeSus(id, T1), "already inactive");
    assertFalse(b.sus(id).orElseThrow().active());
    b.addSus(id, "Steve", "fly", "mod1", T1.plusMillis(1)); assertTrue(a.sus(id).orElseThrow().active());
    assertEquals(List.of(id), a.clearAllSus(T1.plusMillis(2))); assertTrue(a.clearAllSus(T1.plusMillis(3)).isEmpty());
    assertEquals(1, a.allSus().size()); assertEquals(3, a.allSus().get(0).history().size());
    assertEquals(OutboxEvent.Action.SUS, a.outboxAfter(0, 50).get(0).action());
  }
  @Test void susImportIsIdempotent() throws Exception {
    var ds = source(); var store = store(ds); UUID id = UUID.randomUUID();
    var legacy = new NetworkSus(id, "Old", true, T0, T1, List.of(new NetworkSus.Entry("reach", "azicoo", T0)));
    assertTrue(store.importSus(legacy, T1)); assertFalse(store.importSus(legacy, T1));
    assertEquals(1, store.sus(id).orElseThrow().history().size());
  }
  @Test void freezeIsNetworkWideAndRevocable() throws Exception {
    var ds = source(); var store = store(ds); UUID player = UUID.randomUUID();
    var first = of(NetworkPunishment.Type.FREEZE, player, null, T0, null); store.create(first);
    var second = of(NetworkPunishment.Type.FREEZE, player, null, T0.plusMillis(5), null);
    assertEquals(List.of(first.id()), store.create(second), "a new freeze replaces the old one");
    assertEquals(1, store.active(player, T0.plusMillis(6)).size());
    assertEquals(1, store.revokeActive(player, null, EnumSet.of(NetworkPunishment.Type.FREEZE), by("admin")).size());
    assertTrue(store.active(player, T0.plusMillis(30)).isEmpty());
  }
  @Test void outboxPruningKeepsRecentRows() throws Exception {
    var ds = source(); var store = store(ds);
    store.create(of(NetworkPunishment.Type.WARN, UUID.randomUUID(), null, Instant.ofEpochMilli(100), null));
    store.create(of(NetworkPunishment.Type.WARN, UUID.randomUUID(), null, Instant.ofEpochMilli(5000), null));
    assertEquals(1, store.pruneOutbox(Instant.ofEpochMilli(1000)));
    assertEquals(1, store.outboxAfter(0, 10).size());
  }
  @Test void ipNormalization() {
    assertEquals("1.2.3.4", IpAddresses.normalize("/1.2.3.4:25565"));
    assertEquals("0:0:0:0:0:0:0:1", IpAddresses.normalize("::1"));
    assertEquals("2001:db8:0:0:0:0:0:1", IpAddresses.normalize("[2001:DB8::1]:25565"));
    assertEquals("fe80:0:0:0:0:0:0:1", IpAddresses.normalize("fe80::1%eth0"));
    assertNull(IpAddresses.normalize("example.com")); assertNull(IpAddresses.normalize("999.1.1.1")); assertNull(IpAddresses.normalize(""));
  }

  /** Minimal DataSource so the MariaDB run needs no pool library. */
  record DriverManagerSource(String url, String user, String password) implements DataSource {
    public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
    public PrintWriter getLogWriter() { return null; }
    public void setLogWriter(PrintWriter out) {}
    public void setLoginTimeout(int seconds) {}
    public int getLoginTimeout() { return 0; }
    public Logger getParentLogger() { return Logger.getGlobal(); }
    public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
    public boolean isWrapperFor(Class<?> iface) { return false; }
  }
}
