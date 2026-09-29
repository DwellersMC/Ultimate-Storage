const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// The book and quill says what the listing is showing, and bags are not part of that: the two lines about
// how many bags there are and how full the fullest one is have been taken off it.
const retired = ['ults.gui.status.special', 'ults.gui.status.special.pages'];

for (const locale of ['en_us', 'zh_cn', 'zh_tw']) {
  const path = base + locale + '.json';
  const json = JSON.parse(fs.readFileSync(path, 'utf8'));
  let removed = 0;
  for (const key of retired) {
    if (key in json) {
      delete json[key];
      removed++;
    }
  }
  fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
  console.log(locale + ': ' + removed + ' retired, ' + Object.keys(json).length + ' keys');
}
