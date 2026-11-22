// Only use with a disposable flat test world. Commands create and clear fixtures around (8,80,8).
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const [dir, portText, mode] = process.argv.slice(2)
const port = Number(portText)
const allowed = ['ad', 'xray', 'esp', 'suite']
assert(allowed.includes(mode) && Number.isInteger(port))
assert(fs.existsSync(path.join(dir, '.sentinel-test-world')), 'Use a marked disposable world')
const logFile = path.join(dir, 'logs/latest.log')
const bots = []
const fixtures = fs.existsSync(path.join(dir, 'plugins/SentinelTestFixture-1.0.0-paper.jar'))
function adHistory() {
  const file = path.join(dir, 'plugins/SentinelGuard/sus_data.yml')
  return fs.existsSync(file) ? (fs.readFileSync(file,'utf8').match(/staff: SentinelAD/g) || []).length : 0
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const cmd = command => fs.writeFileSync(path.join(dir, 'console'), command + '\n')
async function until(fn, msg, time = 12000) {
  const end = Date.now() + time
  do {
    if (fn()) return
    await wait(50)
  } while (Date.now() < end)
  throw new Error(msg)
}
async function output(command, expected) {
  const offset = fs.statSync(logFile).size
  cmd(command)
  await until(() => fs.readFileSync(logFile).subarray(offset).toString().includes(expected), command + ' expected ' + expected)
}
async function connect(name) {
  const bot = mineflayer.createBot({host:'127.0.0.1', port, version:'1.21.4', username:name, auth:'offline',
    physicsEnabled:false, viewDistance:'tiny', hideErrors:false})
  bots.push(bot)
  bot.on('spawn', () => bot._client.write('player_loaded', {}))
  // A vanilla 1.21.4 client emits the tick-end marker every client tick.
  const ticks = setInterval(() => {
    if (bot._client.state === 'play') bot._client.write('tick_end', {})
  }, 50)
  bot.once('end', () => clearInterval(ticks))
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(name + ' spawn timeout')), 30000)
    bot.once('spawn', () => { clearTimeout(timer); resolve() })
    bot.once('kicked', reason => {clearTimeout(timer); reject(new Error('kick ' + JSON.stringify(reason)))})
    bot.once('error', reject)
  })
  const look = bot.lookAt.bind(bot)
  bot.lookAt = (point) => look(point, true)
  return bot
}
async function fixture() {
  cmd('execute in minecraft:overworld run forceload add 0 0')
  cmd('execute in minecraft:overworld run fill 0 79 0 15 79 15 stone')
  cmd('execute in minecraft:overworld run fill 0 80 0 15 86 15 air')
  cmd('difficulty peaceful')
  await wait(700)
}
async function move(name, x, y, z, dim='overworld') {
  cmd('execute in minecraft:' + dim + ' run tp ' + name + ' ' + [x,y,z,0,0].join(' '))
  await wait(600)
}
function pass(label) { console.log('PASS ' + label) }
async function ad(one, two) {
  await move(one.username, 8.5,80,8.5)
  await move(two.username, 9.5,80,8.5)
  cmd('clear ' + one.username)
  cmd('gamemode survival ' + one.username)
  cmd('setblock 8 80 9 chest')
  cmd('item replace block 8 80 9 container.0 with diamond 13')
  await until(() => one.blockAt(new Vec3(8,80,9))?.name === 'chest', 'chest arrived')
  const chest = await one.openContainer(one.blockAt(new Vec3(8,80,9)))
  await until(() => chest.slots[0]?.count === 13, 'chest contents')
  await one.clickWindow(0,0,1)
  await until(() => one.inventory.items().some(i => i.name === 'diamond' && i.count === 13) ||
    chest.slots.slice(chest.inventoryStart).some(i => i?.name === 'diamond' && i.count === 13), 'shift-click transfer')
  await wait(350)
  chest.close()
  await wait(350)
  await output('data get entity ' + one.username + ' Inventory', 'diamond')
  await output('data get block 8 80 9 Items', '[]')
  pass('ordinary shift-click transferred 13 diamonds and emptied chest')
  cmd('setblock 7 81 9 hopper[facing=down]')
  cmd('setblock 7 80 9 chest')
  cmd('item replace block 7 81 9 container.0 with emerald 7')
  await wait(4200)
  await output('data get block 7 80 9 Items[0].count', ': 7')
  await output('data get block 7 81 9 Items', '[]')
  pass('hopper transferred seven emeralds')
  const beforeTest = adHistory()
  await output('sad testflag ' + one.username, '[test]')
  await output('sad recent', 'other 50%')
  assert.equal(adHistory(), beforeTest)
  pass('test flag reaches recent and console without changing Guard history')
  cmd('item replace entity ' + one.username + ' hotbar.0 with diamond_sword')
  one.setQuickBarSlot(0)
  await until(() => one.heldItem?.name === 'diamond_sword', 'sword in hand')
  cmd('op ' + one.username)
  await wait(300)
  one.chat('/sad seal')
  await until(() => fs.readFileSync(logFile,'utf8').includes('issued server command: /sad seal'), 'seal command')
  await output('data get entity ' + one.username + ' Inventory', 'sentinelad:serial')
  cmd('deop ' + one.username)
  await wait(300)
  // Two administrator-created test fixtures share the same seal.
  cmd('item replace entity ' + one.username + ' hotbar.1 from entity ' + one.username + ' hotbar.0')
  await until(() => one.inventory.slots[37]?.name === 'diamond_sword', 'second fixture')
  const offset = fs.statSync(logFile).size
  await one.clickWindow(36,0,0)
  await until(() => fs.readFileSync(logFile).subarray(offset).toString().includes('uuid-duplicate'), 'duplicate blocked')
  await output('data get entity ' + one.username + ' Inventory[{Slot:0b}].id', 'diamond_sword')
  await output('data get entity ' + one.username + ' Inventory[{Slot:1b}].id', 'diamond_sword')
  pass('repeated item serial blocked before pickup; both original slots retained')
  const audit = path.join(dir, 'plugins/SentinelAD/logs')
  await until(() => fs.readdirSync(audit).some(n => fs.readFileSync(path.join(audit,n),'utf8').includes('type=uuid-duplicate')), 'audit evidence')
  if (mode === 'suite') {
    await until(() => adHistory() === beforeTest + 1, 'one Guard history entry for duplicate seal')
    pass('real finding added exactly one SentinelAD history entry')
    cmd('sg status')
    await output('sg status', 'SentinelAE')
    await output('sad recent', 'uuid-duplicate')
  }
  cmd('setblock 7 81 9 air')
  cmd('setblock 7 80 9 air')
  cmd('setblock 7 80 9 chest')
  cmd('setblock 7 81 9 hopper[facing=down]')
  cmd('item replace block 7 80 9 container.0 from entity ' + one.username + ' hotbar.0')
  cmd('item replace block 7 81 9 container.0 from entity ' + one.username + ' hotbar.1')
  await wait(1200)
  await output('data get block 7 81 9 Items[0].id', 'diamond_sword')
  await output('data get block 7 80 9 Items[0].id', 'diamond_sword')
  cmd('item replace block 7 80 9 container.0 with air')
  await wait(1200)
  await output('data get block 7 81 9 Items', '[]')
  await output('data get block 7 80 9 Items[0].id', 'diamond_sword')
  pass('hopper retained conflicting serial, then delivered to empty destination')
  if (fixtures) {
    cmd('clear ' + one.username)
    cmd('suitefixture oversize ' + one.username)
    await until(() => one.inventory.slots[36]?.count === 2, 'oversized fixture')
    const offset = fs.statSync(logFile).size
    await one.clickWindow(36,0,0)
    await until(() => fs.readFileSync(logFile).subarray(offset).toString().includes('stack-oversize'), 'oversized stack alert')
    await output('data get entity ' + one.username + ' Inventory[{Slot:0b}].count', ': 2')
    await output('suitefixture ordinary-cursor ' + one.username, 'drag cancel=false')
    await output('suitefixture oversize-cursor ' + one.username, 'drag cancel=true')
    await output('suitefixture portal ' + one.username, 'portal cancel=true')
    pass('oversized stack retained; drag and falling-block portal event decisions verified')
    cmd('clear ' + one.username)
    cmd('setblock 8 80 9 shulker_box')
    cmd('item replace block 8 80 9 container.0 with emerald 3')
    await until(() => one.blockAt(new Vec3(8,80,9))?.name === 'shulker_box', 'shulker arrived')
    const shulker = await one.openContainer(one.blockAt(new Vec3(8,80,9)))
    cmd('gamemode creative ' + two.username)
    await wait(300)
    await two.dig(two.blockAt(new Vec3(8,80,9)),true)
    await output('data get block 8 80 9 Items[0].count', ': 3')
    shulker.close()
    await wait(300)
    await two.dig(two.blockAt(new Vec3(8,80,9)),true)
    await output('execute if block 8 80 9 air run say shulker-removed', 'shulker-removed')
    cmd('gamemode survival ' + two.username)
    pass('open shulker survived a real break attempt with contents intact; closed shulker broke')
  }
  cmd('deop ' + one.username)
}
async function xray(one) {
  for (const [dim,ore,cover] of [['overworld','diamond_ore','stone'], ['the_nether','ancient_debris','netherrack']]) {
    const inWorld = 'execute in minecraft:' + dim + ' run '
    await move(one.username, 240,88,240)
    cmd(inWorld + 'forceload add 0 0')
    cmd(inWorld + 'fill 0 79 0 15 79 15 ' + cover)
    cmd(inWorld + 'fill 3 80 3 13 86 13 air')
    cmd(inWorld + 'fill 7 80 7 9 82 9 ' + cover)
    cmd(inWorld + 'setblock 8 81 8 ' + ore)
    await wait(400)
    await move(one.username, 5.5,80,8.5,dim)
    const at = () => one.blockAt(new Vec3(8,81,8))?.name
    await until(() => at() === cover, dim + ' hidden ore, received ' + at())
    await output(inWorld + 'execute if block 8 81 8 ' + ore + ' run say sentinel-ore-present', 'sentinel-ore-present')
    cmd(inWorld + 'setblock 7 81 8 air')
    // Breaking a real neighbouring block triggers the native engine update.
    const neighbor = new Vec3(8,81,7)
    await move(one.username, 8.5,80,5.5,dim)
    cmd('gamemode creative ' + one.username)
    await wait(250)
    await one.dig(one.blockAt(neighbor), true)
    await until(() => at() === ore, dim + ' reveal, received ' + at())
    pass(dim + ' initial chunk concealed ' + ore + ', then revealed the real state')
    cmd('gamemode survival ' + one.username)
  }
}
async function esp(one, two) {
  await fixture()
  await move(one.username, 4.5,80,8.5)
  await move(two.username, 11.5,80,8.5)
  const sees = () => Object.values(one.entities).some(e => e.username === two.username)
  await until(sees,'visible player before wall')
  cmd('fill 8 80 3 8 84 13 stone')
  await until(() => !sees(),'hidden behind opaque wall')
  pass('opaque wall removes other player on client')
  cmd('fill 8 80 3 8 84 13 glass')
  const start = Date.now()
  await until(sees,'visible behind glass')
  pass('glass reveals player after ' + (Date.now()-start) + 'ms observed including console dispatch')
  cmd('fill 8 80 3 8 84 13 stone')
  await until(() => !sees(),'second occlusion')
  cmd('fill 8 80 8 8 81 8 air')
  await until(sees,'visible through doorway')
  pass('opening reveals player')
  if (fixtures) {
    await output('suitefixture hide ' + one.username + ' ' + two.username, 'fixture visibility changed')
    cmd('fill 8 80 3 8 84 13 stone')
    await wait(250)
    cmd('fill 8 80 3 8 84 13 air')
    await wait(300)
    assert(!sees(), 'another plugin still owns hide')
    await output('suitefixture show ' + one.username + ' ' + two.username, 'fixture visibility changed')
    await until(sees, 'independent owner released')
    pass('independent vanish ownership preserved')
  }
  cmd('fill 8 80 3 8 84 13 stone')
  await until(() => !sees(), 'solid wall before reconnect')
  const targetUUID = two.player.uuid
  two.quit()
  await wait(500)
  const leaked = []
  const spawn = p => { if (p.objectUUID === targetUUID || p.entityUUID === targetUUID) leaked.push(p) }
  one._client.on('spawn_entity', spawn)
  const again = await connect(two.username)
  await wait(500)
  assert.equal(leaked.length, 0)
  assert(!sees(), 'reconnected player concealed')
  cmd('fill 8 80 3 8 84 13 air')
  await until(sees, 'reconnected player visible after reveal')
  assert(leaked.length > 0, 'packet observation positive control')
  one._client.off('spawn_entity', spawn)
  pass('no initial spawn packet behind wall on reconnect, positive control after reveal')
  const stands = () => Object.values(one.entities).filter(e => e.name === 'armor_stand').length
  cmd('kill @e[type=armor_stand,tag=sentinel_probe]')
  for (let z=5;z<=12;z++) {
    for (let x=10;x<=12;x++) cmd('summon armor_stand ' + x + ' 80 ' + z + ' {NoGravity:1b,Tags:["sentinel_probe"]}')
  }
  await until(() => stands() >= 24, '24 visible armor stands')
  cmd('fill 8 80 0 8 86 15 stone')
  await until(() => stands() === 0, '24 protected armor stands hidden')
  await output('sae status', 'last tick=')
  pass('24 armor stands occluded; ' + fs.readFileSync(logFile,'utf8').split('\n').filter(l=>l.includes('last tick=')).at(-1))
  cmd('kill @e[type=armor_stand,tag=sentinel_probe]')
  cmd('fill 8 80 0 8 86 15 air')
}
;(async () => {
  await fixture()
  const one = await connect('SuiteOne')
  if (mode === 'xray') await xray(one)
  else {
    const two = await connect('SuiteTwo')
    if (mode === 'ad' || mode === 'suite') await ad(one,two)
    if (mode === 'esp' || mode === 'suite') await esp(one,two)
    if (mode === 'suite') await xray(one)
  }
})().then(() => {for (const b of bots) b.quit(); setTimeout(() => process.exit(0),250)})
.catch(e => {console.error(e); for(const b of bots)b.quit(); setTimeout(() => process.exit(1),250)})
