// Offline localhost test client for the SentinelGuard network smoke test. Never use against real servers.
// usage: node login.cjs <port> <username> <version> <join | kick:TEXT | join-then-kick:TEXT> [timeoutMs]
// join-then-kick prints "JOINED" once in game, then waits for a disconnect containing TEXT.
const mc = require('minecraft-protocol');
const [port, username, version, expect, timeout] = [Number(process.argv[2]), process.argv[3], process.argv[4], process.argv[5], Number(process.argv[6] || 20000)];
if (!Number.isInteger(port) || !username || !version || !expect) throw new Error('usage: login.cjs <port> <username> <version> <expectation> [timeoutMs]');
const [mode, text] = expect.includes(':') ? [expect.slice(0, expect.indexOf(':')), expect.slice(expect.indexOf(':') + 1)] : [expect, ''];

const client = mc.createClient({host: '127.0.0.1', port, username, version, auth: 'offline'});
let joined = false, done = false;
const deadline = setTimeout(() => finish(`timed out (joined=${joined})`), timeout);
function finish(error) {
  if (done) return;
  done = true; clearTimeout(deadline);
  try { client.end(); } catch (_) { /* already closed */ }
  if (error) { console.error(`FAIL ${username}: ${error}`); process.exitCode = 1; }
  else console.log(`PASS ${username}: ${expect}`);
  setTimeout(() => process.exit(), 200);
}
const kicked = packet => {
  const reason = JSON.stringify(packet);
  if (mode === 'join') return finish('unexpected disconnect: ' + reason);
  if (mode === 'join-then-kick' && !joined) return finish('kicked before joining: ' + reason);
  if (!reason.includes(text)) return finish(`disconnect without "${text}": ` + reason);
  finish();
};
client.on('error', error => finish(String(error)));
client.on('end', reason => finish(`connection ended: ${reason}`));
client.on('kick_disconnect', kicked);
client.on('disconnect', kicked);
client.on('ping', packet => client.write('pong', {id: packet.id}));
client.on('position', packet => { if (packet.teleportId !== undefined) client.write('teleport_confirm', {teleportId: packet.teleportId}); });
client.on('packet', (packet, meta) => {
  if (joined || meta.state !== 'play' || meta.name !== 'login') return;
  joined = true;
  if (mode === 'kick') return finish('joined although a ban was expected');
  if (mode === 'join') return setTimeout(() => finish(), 1500);
  console.log('JOINED');
});
