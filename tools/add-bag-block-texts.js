const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// The bag row is drawn as the newest stack it holds: the label rides in the item's own name line, the
// stack's own lines follow it, and one yellow rule closes the block. The rule is kept short — a few
// dashes each side of the label, and a closing rule of the same width — because a long run of them
// reads as noise rather than as a frame.
const texts = {
  en_us: {
    'ults.gui.bag.newest': '───── Latest ─────',
    'ults.gui.bag.divider': '──────────────────',
    'ults.gui.bag.entries.filtered': 'In this bag, matching: ',
  },
  zh_cn: {
    'ults.gui.bag.newest': '───── 最新 ─────',
    'ults.gui.bag.divider': '──────────────',
    'ults.gui.bag.entries.filtered': '袋中符合筛选：',
  },
  zh_tw: {
    'ults.gui.bag.newest': '───── 最新 ─────',
    'ults.gui.bag.divider': '──────────────',
    'ults.gui.bag.entries.filtered': '袋中符合篩選：',
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
