const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// Two settings for how a take-everything behaves, and the two-step notice a stream sends: one when it
// starts pouring, and one when it ends, whichever way it ends.
const texts = {
  en_us: {
    'ults.config.take_all_stacks': 'Stacks one take-everything takes',
    'ults.config.take_all_stacks.tooltip': 'How many stacks one "take everything" takes at most. The default of %s is one backpack\'s worth.\n\nA bigger value takes more per click, and the storage hands it over a tick at a time anyway, so this decides how much one click is for rather than how fast it leaves.',
    'ults.config.take_all_rate': 'Items handed over per tick',
    'ults.config.take_all_rate.tooltip': 'How many items one "take everything" moves per tick while it runs. The default of %s is one stack a tick.\n\nThis is what keeps a warehouse-sized stock from being one enormous operation: a take-everything pours its stock out at this rate until it is done, so the server never has to move a pile in a single tick. Raising it empties a stock faster and asks more of the server for those ticks.',
    'ults.take.start': 'Taking out %s ... %s',
    'ults.take.done': 'Took everything: %s out of the storage.',
    'ults.take.failed': 'Taking out stopped: %s',
    'ults.take.failed.screen': 'you opened a screen.',
    'ults.take.failed.backpack': 'your backpack has no room left.',
    'ults.take.failed.player': 'you are no longer there.',
    'ults.take.failed.empty': 'the storage has nothing left to hand over.',
    'ults.take.failed.replaced': 'another take-out took its place.',
  },
  zh_cn: {
    'ults.config.take_all_stacks': '单次取出上限（组）',
    'ults.config.take_all_stacks.tooltip': '一次「全部取出」最多取走多少组，默认 %s 组（正好一个背包）。\n\n调大每次点击要得更多；真正搬的速度由下面那项决定，所以这一项是"这一下要多少"，不是"多快搬完"。',
    'ults.config.take_all_rate': '每 tick 交出数量',
    'ults.config.take_all_rate.tooltip': '一次「全部取出」在运行期间每 tick 交出多少件，默认 %s 件（一组）。\n\n这正是"清空一个大仓库"不会变成一次巨型操作的原因：全部取出会按这个速度持续交出，直到取完，服务器任何一 tick 都不必搬一整堆。调大能更快清空，代价是那几 tick 对服务器的压力更大。',
    'ults.take.start': '正在取出 %s … 共 %s。',
    'ults.take.done': '取出成功：共取出 %s。',
    'ults.take.failed': '取出失败：%s',
    'ults.take.failed.screen': '你打开了界面。',
    'ults.take.failed.backpack': '背包没有空间了。',
    'ults.take.failed.player': '你已经不在这个世界了。',
    'ults.take.failed.empty': '仓库里已经没有可取的物品了。',
    'ults.take.failed.replaced': '被另一次取出取代了。',
  },
  zh_tw: {
    'ults.config.take_all_stacks': '單次取出上限（組）',
    'ults.config.take_all_stacks.tooltip': '一次「全部取出」最多取走多少組，預設 %s 組（正好一個背包）。\n\n調大每次點擊要得更多；真正搬的速度由下面那項決定，所以這一項是「這一下要多少」，不是「多快搬完」。',
    'ults.config.take_all_rate': '每 tick 交出數量',
    'ults.config.take_all_rate.tooltip': '一次「全部取出」在執行期間每 tick 交出多少件，預設 %s 件（一組）。\n\n這正是「清空一個大倉庫」不會變成一次巨型操作的原因：全部取出會按這個速度持續交出，直到取完，伺服器任何一 tick 都不必搬一整堆。調大能更快清空，代價是那幾 tick 對伺服器的壓力更大。',
    'ults.take.start': '正在取出 %s … 共 %s。',
    'ults.take.done': '取出成功：共取出 %s。',
    'ults.take.failed': '取出失敗：%s',
    'ults.take.failed.screen': '你開啟了介面。',
    'ults.take.failed.backpack': '背包沒有空間了。',
    'ults.take.failed.player': '你已經不在這個世界了。',
    'ults.take.failed.empty': '倉庫裡已經沒有可取的物品了。',
    'ults.take.failed.replaced': '被另一次取出取代了。',
  },
};

for (const [locale, entries] of Object.entries(texts)) {
  const path = base + locale + '.json';
  const json = JSON.parse(fs.readFileSync(path, 'utf8'));
  for (const [key, value] of Object.entries(entries)) {
    json[key] = value;
  }
  fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
  console.log(locale + ': ' + Object.keys(json).length + ' keys');
}
