const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// Requirement 2 removed the left-click shortcut on a bag row, so its wording goes; requirement 3 adds
// the two lines the bag's own book and its empty page need. The rest are the short, official phrasings
// of the labels players read.
const texts = {
  en_us: {
    'ults.gui.title': 'Ultimate Storage',
    'ults.gui.category.special_nbt': 'Special Items',
    'ults.filter.usage': 'Type part of an item name, an item ID or something on it, for example diamond or sharpness.',
    'ults.gui.filter.open': 'Left-click: search by name, ID or what is on it.',
    'ults.special.status.hint': 'Left-click a row: take that stack.',
    'ults.special.status.shown': 'Matching this search: ',
    'ults.special.empty.filter': 'Nothing in this bag matches the search.',
  },
  zh_cn: {
    'ults.gui.title': '终极存储',
    'ults.gui.category.colored_blocks': '染色方块',
    'ults.gui.category.spawn_eggs': '生成蛋',
    'ults.gui.category.special_nbt': '特殊物品',
    'ults.withdraw.mode.item': '物品模式',
    'ults.filter.usage': '在上方输入名称、ID 或携带内容，例如「钻石」或「锋利」。',
    'ults.gui.filter.open': '左键：按名称、ID 或携带内容搜索。',
    'ults.special.status.hint': '左键点该格：取走这一堆。',
    'ults.special.status.shown': '符合筛选：',
    'ults.special.empty.filter': '袋中没有符合筛选的物品。',
  },
  zh_tw: {
    'ults.gui.title': '終極儲存',
    'ults.gui.category.colored_blocks': '染色方塊',
    'ults.gui.category.special_nbt': '特殊物品',
    'ults.withdraw.mode.item': '物品模式',
    'ults.filter.usage': '在上方輸入名稱、ID 或攜帶內容，例如「鑽石」或「鋒利」。',
    'ults.gui.filter.open': '左鍵：按名稱、ID 或攜帶內容搜尋。',
    'ults.special.status.hint': '左鍵點該格：取走這一堆。',
    'ults.special.status.shown': '符合篩選：',
    'ults.special.empty.filter': '袋中沒有符合篩選的物品。',
  },
};

// A key that no screen asks for any more is a translation nobody can reach, so it is dropped rather
// than left behind for a language to fall back out of step with.
const retired = ['ults.gui.take.count.bag'];

for (const [locale, entries] of Object.entries(texts)) {
  const path = base + locale + '.json';
  const json = JSON.parse(fs.readFileSync(path, 'utf8'));
  let changed = 0;
  for (const [key, value] of Object.entries(entries)) {
    if (key in json && json[key] === value) {
      continue;
    }
    json[key] = value;
    changed++;
  }
  for (const key of retired) {
    if (key in json) {
      delete json[key];
      changed++;
    }
  }
  fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
  console.log(locale + ': ' + changed + ' text(s) written, ' + Object.keys(json).length + ' keys');
}
