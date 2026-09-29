const fs = require('fs');
const base = 'src/main/resources/assets/ultimate-storage/lang/';

// A take-everything now sends its own two-step notice from the stream that pours it, so the lines the
// screen used to send are translations nobody can reach any more.
const retired = ['ults.all.success', 'ults.all.taken', 'ults.all.left', 'ults.all.dropped'];

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
