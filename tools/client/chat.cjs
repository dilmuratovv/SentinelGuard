// Offline localhost test client for the SentinelGuard network smoke test. Never use against real servers.
// usage: node chat.cjs <port> <username> <version>
// Prints JOINED once in game. stdin: "say TEXT" sends a chat message, "quit" disconnects and exits 0.
// If the server disconnects the client it prints "DISCONNECTED <reason json>" and exits 0.
const mc = require('minecraft-protocol');
const readline = require('node:readline');
const [port, username, version] = [Number(process.argv[2]), process.argv[3], process.argv[4]];
if (!Number.isInteger(port) || !username || !version) throw new Error('usage: chat.cjs <port> <username> <version>');

const client = mc.createClient({host: '127.0.0.1', port, username, version, auth: 'offline'});
let joined = false, done = false;
function finish(line) {
  if (done) return;
  done = true;
  if (line) console.log(line);
  try { client.end(); } catch (_) { /* already closed */ }
  setTimeout(() => process.exit(0), 200);
}
const kicked = packet => finish('DISCONNECTED ' + JSON.stringify(packet));
client.on('kick_disconnect', kicked);
client.on('disconnect', kicked);
client.on('error', error => finish('DISCONNECTED ' + JSON.stringify(String(error))));
client.on('end', reason => finish('DISCONNECTED ' + JSON.stringify(String(reason))));
client.on('ping', packet => client.write('pong', {id: packet.id}));
client.on('position', packet => { if (packet.teleportId !== undefined) client.write('teleport_confirm', {teleportId: packet.teleportId}); });
client.on('packet', (packet, meta) => {
  if (joined || meta.state !== 'play' || meta.name !== 'login') return;
  joined = true;
  console.log('JOINED');
});
readline.createInterface({input: process.stdin}).on('line', line => {
  if (line.startsWith('say ')) { if (joined) client.chat(line.slice(4)); }
  else if (line.trim() === 'quit') finish('');
});
