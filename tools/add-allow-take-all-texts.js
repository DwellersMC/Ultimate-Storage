const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

const texts = {
  en_us: {
    'ults.config.allow_take_all': 'Allow taking everything',
    'ults.config.allow_take_all.tooltip': 'Whether players may empty a stock out with "take everything" at all.\n\nWith it off the offer is not there: the hint on a row and on a bag\'s status book leaves the line out, and the clicks that would start one do nothing — no screen, no sound and no message.',
  },
  zh_cn: {
    'ults.config.allow_take_all': '允许全部取出',
    'ults.config.allow_take_all.tooltip': '是否允许玩家用「全部取出」把一种物品整批取空。\n\n关闭时这个入口就不存在了：物品行和收纳袋状态书上的那行提示不再显示，本来会打开它的点击也不做任何事——不弹界面、不发声、不发消息。',
  },
  zh_tw: {
    'ults.config.allow_take_all': '允許全部取出',
    'ults.config.allow_take_all.tooltip': '是否允許玩家用「全部取出」把一種物品整批取空。\n\n關閉時這個入口就不存在了：物品列和收納袋狀態書上的那行提示不再顯示，本來會開啟它的點擊也不做任何事——不彈介面、不發聲、不發訊息。',
  },
};

// Taking everything is no longer interrupted by opening a screen, so that reason has no message.
const retired = ['ults.take.failed.screen'];

for (const [locale, entries] of Object.entries(texts)) {
  const path = base + locale + '.json';
  const json = JSON.parse(fs.readFileSync(path, 'utf8'));
  for (const [key, value] of Object.entries(entries)) {
    json[key] = value;
  }
  for (const key of retired) {
    delete json[key];
  }
  fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
  console.log(locale + ': ' + Object.keys(json).length + ' keys');
}
