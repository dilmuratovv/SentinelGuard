#!/bin/bash
# Whole-suite regression: every plugin's own live test, run with the other four signed plugins installed on the
# same server. Usage: suite.sh [versions...] (default: 26.3 1.21.11 1.21.1); ONLY=ae,axray,refresh,mining,ad,guard
L=/home/kali/MC/sentinel-testlab; S="/home/kali/MC/0plugins/Sentinel Suite"; W=$L/suite-final
AC="$S/SentinelAC/builds/SentinelAC-2.8.0-paper.jar"; AD="$S/SentinelAD/builds/SentinelAD-1.3.1-paper.jar"
AE="$S/SentinelAE/builds/SentinelAE-2.1.2-paper.jar"; AX="$S/SentinelAX-ray/builds/SentinelAX-ray-2.2.2-paper.jar"
GD="$S/SentinelGuard/builds/SentinelGuard-2.3.2-paper.jar"
J21=/usr/lib/jvm/java-21-openjdk-amd64/bin/java; J25=/usr/lib/jvm/java-25-openjdk-amd64/bin/java
C=$L/smoke/matrix-smoke/shared-cache; VV=$L/testdeps/ViaVersion-5.12.2.jar; VB=$L/testdeps/ViaBackwards-5.12.2.jar
ONLY="${ONLY:-ae,axray,refresh,mining,ad,guard}"
# Lab only: LIB_MIRROR=http://127.0.0.1:8765/ (e.g. python3 -m http.server over ~/.m2/repository) lets every fresh test
# server get SentinelGuard's libraries (HikariCP, MariaDB, Jedis) locally instead of from Maven Central each time.
[ -n "$LIB_MIRROR" ] && export JAVA_TOOL_OPTIONS="-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=$LIB_MIRROR"
PR=$L/refresh-probe/SentinelRefreshProbe.jar; LP="--luckperms-stub $L/guard-test/lpstub/LuckPerms-stub.jar"
want() { [[ ",$ONLY," == *",$1,"* ]]; }
# scripted bots of the AE/AX-ray/AD probes teleport, so they get AC's exempt permission (AC still runs, sees every
# packet and keeps its PacketEvents pipeline in place); the Guard test runs with AC fully enforcing.
others() { OTH=(--via $W/SuiteExempt.jar); for j in "$AC" "$AD" "$AE" "$AX" "$GD"; do [ "$j" != "$1" ] && OTH+=(--via "$j"); done; }
for ver in ${@:-26.3 1.21.11 1.21.1}; do
  case $ver in
    26.2) jar=paper-26.2-135.jar; java=$J25; via="--via $VV --via $VB"; cv=1.21.11; adcv=26.1; boat=oak_boat;;
    26.3) jar=paper-26.3-230.jar; java=$J25; via="--via $VV --via $VB"; cv=1.21.11; adcv=26.1; boat=oak_boat;;
    1.21.11) jar=paper-1.21.11-132.jar; java=$J21; via=""; cv=1.21.11; adcv=1.21.11; boat=oak_boat;;
    1.21.1) jar=paper-1.21.1-133.jar; java=$J21; via="--via $VV"; cv=1.21.11; adcv=1.21.11; boat=boat;;
  esac
  P=$L/testdeps/$jar
  # lab only: Paper 1.21.1 ignores LIB_MIRROR, so fresh servers get the libraries of an earlier run if one is kept
  if [ -d "$L/libseed/$ver" ]; then export SENTINEL_LIBS_SEED="$L/libseed/$ver"; else unset SENTINEL_LIBS_SEED; fi
  if want ae; then others "$AE"; echo "== $ver AE + others"; (cd "$S/SentinelAE/code"; NODE_PATH=$L/node-ae/node_modules timeout 1200 python3 tools/ae_probe.py --paper $P --java $java --jar "$AE" "${OTH[@]}" $via --mojang-cache $C --work $W/ae-$ver --client-version $cv --boat $boat --restart-first --accept-eula 2>&1 | grep -E "FAIL|DONE|Traceback|Error|error:"); fi
  if want axray; then others "$AX"; echo "== $ver AX-ray + others"; (cd "$S/SentinelAX-ray/code"; NODE_PATH=$L/node-ae/node_modules timeout 1200 python3 tools/axray_probe.py --paper $P --java $java --jar "$AX" "${OTH[@]}" $via --mojang-cache $C --work $W/ax-$ver --client-version $cv --accept-eula 2>&1 | grep -E "FAIL|DONE|Traceback|Error|error:"); fi
  if want refresh; then
    others "$AE"; echo "== $ver AE shared-refresh + others"; (cd "$S/SentinelAE/code"; NODE_PATH=$L/node-ae/node_modules timeout 1200 python3 tools/refresh_probe.py --mode storage --paper $P --java $java --jar "$AE" --probe $PR "${OTH[@]}" $via --mojang-cache $C --work $W/aer-$ver --client-version $cv --accept-eula 2>&1 | grep -E "FAIL|DONE|Traceback|Error|error:")
    others "$AX"; echo "== $ver AX-ray shared-refresh + others"; (cd "$S/SentinelAX-ray/code"; NODE_PATH=$L/node-ae/node_modules timeout 1200 python3 tools/refresh_probe.py --mode ore --paper $P --java $java --jar "$AX" --probe $PR "${OTH[@]}" $via --mojang-cache $C --work $W/axr-$ver --client-version $cv --accept-eula 2>&1 | grep -E "FAIL|DONE|Traceback|Error|error:")
  fi
  if want mining; then others "$AX"; echo "== $ver AX-ray mining + others"; (cd "$S/SentinelAX-ray/code"; NODE_PATH=$L/node-ae/node_modules timeout 1200 python3 tools/mining_probe.py --paper $P --java $java --jar "$AX" --harness $L/axray-test/SentinelAXrayHarness.jar "${OTH[@]}" $via --mojang-cache $C --work $W/mining-$ver --client-version $cv --accept-eula 2>&1 | grep -E "FAIL|DONE|flag|Traceback|Error" | head -12); fi
  if want ad; then others "$AD"; echo "== $ver AD + others"; (cd "$S/SentinelAD/code"; NODE_PATH="$S/SentinelAC/code/tools/client/node_modules" timeout 1200 python3 tools/dupe_probe.py --paper $P --java $java --jar "$AD" --harness $L/ad-test/h131/SentinelADHarness.jar "${OTH[@]}" $via --mojang-cache $C --work $W/ad-$ver --client-version $adcv --accept-eula 2>&1 | grep -E "FAIL|SKIP|DONE|Traceback"); fi
  if want guard; then echo "== $ver Guard + others"; (cd "$S/SentinelGuard/code"; NODE_PATH="$S/SentinelAC/code/tools/client/node_modules" timeout 1800 python3 tools/live/guard_live.py --paper $P --java $java --jar "$GD" --probe $L/guard-test/GuardProbe.jar --ac "$AC" $LP --via "$AD" --via "$AE" --via "$AX" $via --mojang-cache $C --client-version $adcv --work $W/guard-$ver --accept-eula 2>&1 | grep -E "^(FAIL|DONE)"); fi
done
echo "== SUITE DONE"
