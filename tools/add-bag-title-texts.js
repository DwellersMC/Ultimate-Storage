const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// The bag row's first line names the bag itself: the item it holds, in the brackets the language uses,
// with the word for a bag after it. The item's name is not written here — the line holds a %s that the
// row fills with the item's own name component, so the client draws it in the player's own language.
const texts = {
  en_us: {
    'ults.gui.bag.title': '[%s] Bag',
  },
  zh_cn: {
    'ults.gui.bag.title': '【%s】收纳袋',
  },
  zh_tw: {
    'ults.gui.bag.title': '【%s】收納袋',
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
