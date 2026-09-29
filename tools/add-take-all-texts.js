const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// What one confirmation of "take everything" really moves, and what it cannot. A stock can hold more
// than any backpack, so the screen says both numbers and the chat says what came out.
const texts = {
  en_us: {
    'ults.all.take': 'This click: ',
    'ults.all.take.pack': 'Into the backpack: ',
    'ults.all.take.ground': 'On the ground: ',
    'ults.all.take.left': 'Stays in storage: ',
    'ults.all.confirm.full': 'No room in the backpack',
    'ults.all.taken': 'Took %s of %s.',
    'ults.all.left': '%s stays in storage: the backpack cannot take more.',
    'ults.all.dropped': '%s dropped on the ground.',
  },
  zh_cn: {
    'ults.all.take': '本次取出：',
    'ults.all.take.pack': '放进背包：',
    'ults.all.take.ground': '掉在脚下：',
    'ults.all.take.left': '留在仓库：',
    'ults.all.confirm.full': '背包没有空间',
    'ults.all.taken': '已取出 %s / %s。',
    'ults.all.left': '%s 留在仓库：背包装不下了。',
    'ults.all.dropped': '%s 掉在脚下。',
  },
  zh_tw: {
    'ults.all.take': '本次取出：',
    'ults.all.take.pack': '放進背包：',
    'ults.all.take.ground': '掉在腳下：',
    'ults.all.take.left': '留在倉庫：',
    'ults.all.confirm.full': '背包沒有空間',
    'ults.all.taken': '已取出 %s / %s。',
    'ults.all.left': '%s 留在倉庫：背包裝不下了。',
    'ults.all.dropped': '%s 掉在腳下。',
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
