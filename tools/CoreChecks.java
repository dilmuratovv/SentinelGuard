import java.time.Instant;
import java.util.UUID;
import uz.sentinel.guard.network.NetworkPunishment;
public class CoreChecks {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    Instant start=Instant.parse("2026-01-01T00:00:00Z"), end=start.plusSeconds(60);
    UUID id=UUID.randomUUID(), player=UUID.randomUUID();
    var p=new NetworkPunishment(id,player,NetworkPunishment.Type.BAN,"reason","azicoo","survival",start,end,false);
    check(!p.activeAt(start.minusSeconds(1))); check(p.activeAt(start)); check(p.activeAt(end.minusMillis(1))); check(!p.activeAt(end));
    var perm=new NetworkPunishment(id,player,NetworkPunishment.Type.BAN,"reason","azicoo","survival",start,null,false);
    check(perm.activeAt(start.plusSeconds(9999999)));
    var revoked=new NetworkPunishment(id,player,NetworkPunishment.Type.BAN,"reason","azicoo","survival",start,null,true);
    check(!revoked.activeAt(start));
    try { new NetworkPunishment(id,player,NetworkPunishment.Type.BAN,"reason","azicoo","survival",start,start,false); throw new AssertionError(); } catch(IllegalArgumentException expected) {}
    System.out.println("7 punishment boundary checks passed");
  }
}
