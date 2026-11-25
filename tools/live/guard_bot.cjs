// Test-only bot for the SentinelGuard live test. Localhost, offline mode. Never point it at a real server.
// usage: node guard_bot.cjs <host> <port> <name> <version>
// stdin: "cmd <command>", "chat <text>", "move <dx> <dy> <dz>", "quit", "hang", "sign <x> <y> <z> <text>",
//        "book <slot> <text>", "rename <name>", "attack <entityId>".
// stdout: JOINED, CHAT <text>, KICK <reason>, POS <x> <y> <z>, OPEN_SIGN <x> <y> <z>, END.
const mc = require('minecraft-protocol');
const readline = require('node:readline');
const [host, port, name, version] = [process.argv[2], Number(process.argv[3]), process.argv[4], process.argv[5]];
const toServer = require('minecraft-data')(version).protocol.play.toServer.types;
const usesFlags = toServer.packet_position[1].some(f => f.name === 'flags');
let ground = usesFlags ? {flags: {onGround: true, hasHorizontalCollision: false}} : {onGround: true};
const setGround = on => { ground = usesFlags ? {flags: {onGround: on, hasHorizontalCollision: false}} : {onGround: on}; };
const client = mc.createClient({host, port, username: name, version, auth: 'offline', keepAlive: false});
let position = null, hanging = false, joined = false;
const say = s => process.stdout.write(s + '\n');
function text(component) {
  if (component == null) return '';
  if (typeof component === 'string') {
    try { return text(JSON.parse(component)); } catch (_) { return component; }
  }
  if (Array.isArray(component)) return component.map(text).join('');
  let out = '';
  if (component.value !== undefined && component.type) return text(component.value); // nbt
  if (typeof component !== 'object') return String(component);
  if (component.text !== undefined) out += text(component.text);
  if (component[''] !== undefined) out += text(component['']);
  if (component.translate) out += text(component.translate) + (component.with ? ' ' + (Array.isArray(component.with) ? component.with.map(text).join(' ') : text(component.with)) : '');
  if (component.extra) out += text(component.extra);
  return out;
}
client.on('keep_alive', p => { if (!hanging) client.write('keep_alive', {keepAliveId: p.keepAliveId}); });
client.on('ping', p => { if (!hanging) client.write('pong', {id: p.id}); });
let selfId = 0, sequence = 0;
client.on('login', p => { joined = true; selfId = p.entityId; say('JOINED'); });
const safe = f => (...args) => { try { f(...args); } catch (e) { say('PARSE ' + e.message); } };
client.on('system_chat', safe(p => say('CHAT ' + text(p.content).replace(/\n/g, ' | '))));
client.on('player_chat', safe(p => say('CHAT ' + text(p.plainMessage || p.unsignedChatContent || ''))));
client.on('kick_disconnect', safe(p => say('KICK ' + text(p.reason).replace(/\n/g, ' | '))));
client.on('disconnect', safe(p => say('KICK ' + text(p.reason).replace(/\n/g, ' | '))));
client.on('open_sign_entity', p => say(`OPEN_SIGN ${p.location.x} ${p.location.y} ${p.location.z}`));
client.on('error', e => say('ERROR ' + e.message));
const entityNames = require('minecraft-data')(version).entities;
client.on('spawn_entity', p => say(`ENTITY ${p.entityId} ${(entityNames[p.type] || {}).name || p.type}`));
client.on('end', () => { say('END'); setTimeout(() => process.exit(0), 100); });
client.on('position', packet => {
  if (process.env.GB_DEBUG) say('RAWPOS ' + JSON.stringify(packet));
  const previous = position || {x: 0, y: 0, z: 0, yaw: 0, pitch: 0};
  position = {};
  for (const axis of ['x', 'y', 'z', 'yaw', 'pitch'])
    position[axis] = (packet.flags?.[axis] ? previous[axis] : 0) + packet[axis];
  if (packet.teleportId !== undefined) client.write('teleport_confirm', {teleportId: packet.teleportId});
  client.write('position_look', {...position, ...ground});
  say(`POS ${position.x.toFixed(2)} ${position.y.toFixed(2)} ${position.z.toFixed(2)}`);
});
const walkQueue = [];
setInterval(() => {
  if (!position || hanging || !joined) return;
  const step = walkQueue.shift();
  if (step) position = {...position, x: position.x + step[0], y: position.y + step[1], z: position.z + step[2]};
  if (![position.x, position.y, position.z].every(Number.isFinite)) { say('BADPOS ' + JSON.stringify(position)); return; }
  client.write('position', {x: position.x, y: position.y, z: position.z, ...ground});
  if (toServer.packet_tick_end) client.write('tick_end', {});
}, 50);
readline.createInterface({input: process.stdin}).on('line', line => {
  const [verb, ...rest] = line.trim().split(' ');
  const arg = rest.join(' ');
  switch (verb) {
    case 'cmd': client.chat('/' + arg); break;
    case 'chat': client.chat(arg); break;
    case 'move': {
      // walk in small steps, one per tick, as a client would
      const [dx, dy, dz] = rest.map(Number);
      const steps = Math.max(1, Math.ceil(Math.hypot(dx, dy, dz) / 0.2));
      for (let i = 0; i < steps; i++) walkQueue.push([dx / steps, dy / steps, dz / steps]);
      break;
    }
    case 'quit': client.end(); break;
    case 'hang': hanging = true; say('HANGING'); break;
    case 'sign': {
      const [x, y, z, ...words] = rest;
      client.write('update_sign', {location: {x: +x, y: +y, z: +z}, isFrontText: true, text1: words.join(' '), text2: 'line two', text3: '', text4: ''});
      break;
    }
    case 'book': {
      // as a real client: open the book (use item), then send the edit a moment later
      const [slot, ...words] = rest;
      const main = JSON.stringify(toServer.packet_use_item).includes('main_hand') ? 'main_hand' : 0;
      client.write('use_item', {hand: main, sequence: ++sequence, rotation: {x: position ? position.yaw : 0, y: position ? position.pitch : 0}});
      setTimeout(() => client.write('edit_book', {hand: +slot, pages: [words.join(' ')], title: undefined}), 400);
      break;
    }
    case 'rename': client.write('name_item', {name: arg}); break;
    case 'air': setGround(arg !== 'on'); break;
    case 'elytra': client.write('entity_action', {entityId: selfId, actionId: 'start_fall_flying', jumpBoost: 0}); break;
    case 'attack':
      if (toServer.packet_attack) client.write('attack', {entityId: +arg});
      else client.write('use_entity', {target: +arg, mouse: 1, sneaking: false});
      client.write('arm_animation', {hand: JSON.stringify(toServer.packet_arm_animation).includes('main_hand') ? 'main_hand' : 0});
      break;
  }
});
