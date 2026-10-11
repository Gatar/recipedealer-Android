// Runs the production extractor.js (+ Readability.js) against each androidTest HTML fixture in jsdom (and each
// .txt fixture loaded through RecipeExtractor.loadText, as for text shares) and
// writes the output to app/src/test/resources/extractor-output/<fixture>.json, so the JVM tests can check
// the JS -> Java contract. jsdom is not a WebView: the instrumented ExtractorJsTest remains the real check.
//   usage: npm install && node generate-extractor-fixtures.js
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const root = path.join(__dirname, '..', 'app', 'src');
const fixturesDir = path.join(root, 'androidTest', 'assets', 'fixtures');
const outDir = path.join(root, 'test', 'resources', 'extractor-output');
const readability = fs.readFileSync(path.join(root, 'main', 'assets', 'Readability.js'), 'utf8');
const extractor = fs.readFileSync(path.join(root, 'main', 'assets', 'extractor.js'), 'utf8');
const SETTLE_MS = { 'spa-delayed': 2600 };

async function main() {
  fs.mkdirSync(outDir, { recursive: true });
  for (const file of fs.readdirSync(fixturesDir).filter(f => /\.(html|txt)$/.test(f))) {
    const name = file.replace(/\.(html|txt)$/, '');
    const isText = file.endsWith('.txt');
    const content = fs.readFileSync(path.join(fixturesDir, file), 'utf8');
    const dom = new JSDOM(isText ? '<!DOCTYPE html><html><head></head><body></body></html>' : content, {
      url: 'https://example.test/' + name, runScripts: 'dangerously', pretendToBeVisual: true
    });
    await new Promise(r => setTimeout(r, SETTLE_MS[name] || 100));
    dom.window.eval(readability + '\n;\n' + extractor);
    if (isText) dom.window.eval('window.RecipeExtractor.loadText(' + JSON.stringify(content) + ')');
    const result = dom.window.eval('JSON.stringify(window.RecipeExtractor.run())');
    fs.writeFileSync(path.join(outDir, name + '.json'), JSON.stringify(JSON.parse(result), null, 2) + '\n');
    const o = JSON.parse(result);
    console.log(name.padEnd(28), 'jsonLd=' + o.jsonLd.length, 'microdata=' + o.microdata.length,
      'wprm=' + (o.pluginHits.wprm && o.pluginHits.wprm.found),
      'heur.ingr=' + JSON.stringify(o.heuristic.ingredients.map(g => [g.name, g.items.length])),
      'heur.instr=' + JSON.stringify(o.heuristic.instructions.map(g => [g.name, g.items.length])),
      'servings=' + o.heuristic.servings, 'notes=' + o.heuristic.notes.length,
      'errors=' + JSON.stringify(o.errors));
    dom.window.close();
  }
}
main().catch(e => { console.error(e); process.exit(1); });
