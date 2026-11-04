import java.time.Instant;
import java.util.UUID;
import uz.sentinel.guard.network.IpAddresses;
import uz.sentinel.guard.network.NetworkPunishment;
public class CoreChecks {
  static int checks;
  static void check(boolean value) { if (!value) throw new AssertionError("check " + (checks + 1)); checks++; }
  static NetworkPunishment of(NetworkPunishment.Type type, Instant start, Instant end, NetworkPunishment.Revocation revocation) {
    return new NetworkPunishment(UUID.randomUUID(), UUID.randomUUID(), "Steve", null, type, "reason", null, "azicoo", "survival", start, end, false, revocation);
  }
  static void rejects(Runnable r) { try { r.run(); throw new AssertionError("expected rejection"); } catch (IllegalArgumentException expected) { checks++; } }
  public static void main(String[] args) {
    Instant start = Instant.parse("2026-01-01T00:00:00Z"), end = start.plusSeconds(60);
    var p = of(NetworkPunishment.Type.BAN, start, end, null);
    check(p.activeAt(start.minusSeconds(5)) ); /* a server whose clock lags must still enforce */ check(p.activeAt(start)); check(p.activeAt(end.minusMillis(1))); check(!p.activeAt(end));
    check(of(NetworkPunishment.Type.BAN, start, null, null).activeAt(start.plusSeconds(9999999)));
    check(!of(NetworkPunishment.Type.BAN, start, null, new NetworkPunishment.Revocation("a", "b", start)).activeAt(start));
    check(!of(NetworkPunishment.Type.KICK, start, null, null).activeAt(start));
    check(of(NetworkPunishment.Type.FREEZE, start, null, null).activeAt(start.plusSeconds(1)));
    rejects(() -> of(NetworkPunishment.Type.BAN, start, start, null));
    rejects(() -> new NetworkPunishment(UUID.randomUUID(), null, null, null, NetworkPunishment.Type.IP_BAN, "r", null, "a", "s", start, null, false, null));
    rejects(() -> new NetworkPunishment(UUID.randomUUID(), null, null, "::1", NetworkPunishment.Type.IP_BAN, "r", null, "a", "s", start, null, false, null));
    check("1.2.3.4".equals(IpAddresses.normalize("/1.2.3.4:25565")));
    check("2001:db8:0:0:0:0:0:1".equals(IpAddresses.normalize("[2001:db8::1]:1")));
    check(IpAddresses.normalize("localhost") == null);
    System.out.println(checks + " punishment boundary checks passed");
  }
}
