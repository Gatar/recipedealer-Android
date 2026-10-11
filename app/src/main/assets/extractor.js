// Recipe extractor. Runs ALL stages (no short-circuit) and returns one plain object.
// Requires Readability.js to be evaluated first (global `Readability`).
// Usage: JSON.stringify(window.RecipeExtractor.run())
(function (global) {
  'use strict';

  // ---- configuration (edit selectors here) -----------------------------------------------------
  var CONFIG = {
    // Each plugin: a container selector and per-field selectors. `sel` is one selector or an ordered list of
    // fallbacks (the first one that matches anything wins). `multiple` fields return every match;
    // `attr` reads an attribute instead of text. `lines` also splits each match at <br> (one item per line).
    // `group` / `groupName`: when the container has group elements,
    // the field becomes [{name, items}] with the matches of each group.
    // TODO: groups for Tasty / Mediavine once a live grouped recipe is seen.
    plugins: {
      wprm: { // WP Recipe Maker
        container: '.wprm-recipe-container, .wprm-recipe',
        fields: {
          name: { sel: '.wprm-recipe-name' },
          recipeIngredient: { sel: '.wprm-recipe-ingredient', multiple: true,
                              group: '.wprm-recipe-ingredient-group', groupName: '.wprm-recipe-group-name' },
          recipeInstructions: { sel: '.wprm-recipe-instruction-text', multiple: true,
                                group: '.wprm-recipe-instruction-group', groupName: '.wprm-recipe-group-name' },
          recipeYield: { sel: ['.wprm-recipe-servings', '.wprm-recipe-servings-container'] },
          prepTime: { sel: ['.wprm-recipe-prep-time-container', '.wprm-recipe-prep_time'] },
          cookTime: { sel: ['.wprm-recipe-cook-time-container', '.wprm-recipe-cook_time'] },
          totalTime: { sel: ['.wprm-recipe-total-time-container', '.wprm-recipe-total_time'] },
          image: { sel: '.wprm-recipe-image img', attr: 'src' },
          // One item per paragraph / block span; the whole container only when it has no such children.
          notes: { sel: ['.wprm-recipe-notes > span, .wprm-recipe-notes > p, .wprm-recipe-notes li', '.wprm-recipe-notes'],
                   multiple: true, lines: true }
        }
      },
      tasty: { // Tasty Recipes
        container: '.tasty-recipes',
        fields: {
          name: { sel: '.tasty-recipes-title' },
          recipeIngredient: { sel: '.tasty-recipes-ingredients li', multiple: true },
          recipeInstructions: { sel: '.tasty-recipes-instructions li', multiple: true },
          recipeYield: { sel: '.tasty-recipes-yield' },
          prepTime: { sel: '.tasty-recipes-prep-time' },
          cookTime: { sel: '.tasty-recipes-cook-time' },
          totalTime: { sel: '.tasty-recipes-total-time' },
          image: { sel: '.tasty-recipes-image img', attr: 'src' },
          notes: { sel: ['.tasty-recipes-notes-body p, .tasty-recipes-notes-body li', '.tasty-recipes-notes-body',
                         '.tasty-recipes-notes'], multiple: true, lines: true }
        }
      },
      mediavine: { // Mediavine Create
        container: '.mv-create-wrapper',
        fields: {
          name: { sel: '.mv-create-title' },
          recipeIngredient: { sel: '.mv-create-ingredients li', multiple: true },
          recipeInstructions: { sel: '.mv-create-instructions li', multiple: true },
          recipeYield: { sel: '.mv-create-time-yield' },
          prepTime: { sel: '.mv-create-time-prep' },
          cookTime: { sel: ['.mv-create-time-active', '.mv-create-time-cook'] },
          totalTime: { sel: '.mv-create-time-total' },
          image: { sel: '.mv-create-image', attr: 'src' },
          notes: { sel: ['.mv-create-notes-content p, .mv-create-notes-content li', '.mv-create-notes-content',
                         '.mv-create-notes'], multiple: true, lines: true }
        }
      }
    },
    // Heading keywords for the heuristic stage (matched as lower-case substrings, PL + EN).
    headings: {
      ingredients: ['składniki', 'skladniki', 'ingredients'],
      // "przygotow" is a stem: "przygotowanie", "sposób przygotowania", "jak przygotować". Multi-stage recipes add
      // stage sections ("Składanie tortu", ChatGPT).
      instructions: ['przygotow', 'wykonanie', 'kroki', 'krok po kroku', 'składanie', 'skladanie', 'montaż',
                     'instructions', 'method', 'directions', 'steps', 'preparation', 'assembly'],
      notes: ['wskazówki', 'wskazowki', 'porady', 'uwagi', 'notes', 'tips']
    },
    maxHeadingLength: 60,
    // A section heading that names a part: "Składniki na polewę", "Ingredients for the sauce".
    groupHeading: /\s(na|do|dla|for)\s+\S/i,
    // Longer labels are treated as ordinary text, not as group names ("Ciasto", "Do dekoracji").
    maxSubheadingLength: 40,
    servingsLine: /^\d+(\s*[-–]\s*\d+)?\s*(porcj|os[oó]b|sztuk|servings?\b|people\b)/i
  };

  // ---- helpers ---------------------------------------------------------------------------------
  function norm(s) {
    return (s || '').replace(/[ ​‌‍﻿]/g, ' ').replace(/\s+/g, ' ').trim();
  }
  function meta(sel, attr) {
    var el = document.querySelector(sel);
    return el ? norm(el.getAttribute(attr)) : null;
  }
  function queryAll(root, sel) {
    try { return Array.prototype.slice.call(root.querySelectorAll(sel)); } catch (e) { return []; }
  }

  // ---- stage: JSON-LD --------------------------------------------------------------------------
  function extractJsonLd(errors) {
    var out = [];
    queryAll(document, 'script[type="application/ld+json"]').forEach(function (s, i) {
      var raw = s.textContent || '';
      try {
        out.push(JSON.parse(raw));
      } catch (e) {
        // Common breakage: HTML comments / CDATA wrappers around the JSON.
        try {
          out.push(JSON.parse(raw.replace(/^\s*(<!--|\/\/\s*<!\[CDATA\[)/, '').replace(/(-->|\/\/\s*\]\]>)\s*$/, '')));
        } catch (e2) {
          errors.push('jsonLd[' + i + '] parse error: ' + e2.message);
        }
      }
    });
    return out;
  }

  // ---- stage: microdata ------------------------------------------------------------------------
  function microdataValue(el) {
    var tag = el.tagName.toLowerCase();
    if (el.hasAttribute('content')) return norm(el.getAttribute('content'));
    if (tag === 'meta') return norm(el.getAttribute('content'));
    if (tag === 'a' || tag === 'link' || tag === 'area') return el.getAttribute('href') || '';
    if (/^(img|audio|video|source|embed|iframe|track)$/.test(tag)) return el.getAttribute('src') || '';
    if (tag === 'time') return norm(el.getAttribute('datetime') || el.textContent);
    return norm(el.textContent);
  }
  // Single-valued properties become scalars, multi-valued ones stay arrays (at every nesting level).
  function flattenProps(props) {
    var item = {};
    Object.keys(props).forEach(function (k) {
      item[k] = props[k].length === 1 ? props[k][0] : props[k];
    });
    return item;
  }
  function collectProps(scope, props) {
    Array.prototype.forEach.call(scope.children, function (child) {
      var names = (child.getAttribute('itemprop') || '').split(/\s+/).filter(Boolean);
      var value;
      if (names.length) {
        if (child.hasAttribute('itemscope')) {
          value = flattenProps(collectProps(child, {}));
          var type = child.getAttribute('itemtype');
          if (type) value['@type'] = type.replace(/^.*\//, '');
        } else {
          value = microdataValue(child);
        }
        names.forEach(function (n) { (props[n] = props[n] || []).push(value); });
        if (!child.hasAttribute('itemscope')) collectProps(child, props);
      } else if (!child.hasAttribute('itemscope')) {
        collectProps(child, props);
      }
    });
    return props;
  }
  function extractMicrodata() {
    return queryAll(document, '[itemscope][itemtype*="schema.org/Recipe"]').map(function (scope) {
      var item = flattenProps(collectProps(scope, {}));
      item['@type'] = 'Recipe';
      return item;
    });
  }

  // ---- stage: plugin selectors -----------------------------------------------------------------
  function pluginValues(root, f) {
    var els = [];
    [].concat(f.sel).some(function (sel) { els = queryAll(root, sel); return els.length > 0; });
    var values = [];
    els.forEach(function (el) {
      if (f.attr) values.push(el.getAttribute(f.attr) || el.getAttribute('data-src') || '');
      else if (f.lines) textLines(el).forEach(function (t) { values.push(t); });
      else values.push(norm(el.textContent));
    });
    return values.filter(Boolean);
  }
  // Text of `el` split at <br>.
  function textLines(el) {
    var copy = el.cloneNode(true);
    queryAll(copy, 'br').forEach(function (br) { br.parentNode.replaceChild(document.createTextNode('\n'), br); });
    return copy.textContent.split('\n').map(norm).filter(Boolean);
  }
  function extractPlugins() {
    var hits = {};
    Object.keys(CONFIG.plugins).forEach(function (name) {
      var cfg = CONFIG.plugins[name];
      var containers = queryAll(document, cfg.container);
      var hit = { found: containers.length > 0, containerCount: containers.length, fieldMatches: {}, recipe: {} };
      if (containers.length) {
        var root = containers[0];
        Object.keys(cfg.fields).forEach(function (field) {
          var f = cfg.fields[field];
          var groupEls = f.group ? queryAll(root, f.group) : [];
          if (groupEls.length) {
            var groups = groupEls.map(function (g) {
              var nameEl = g.querySelector(f.groupName);
              return { name: nameEl ? (norm(nameEl.textContent) || null) : null, items: pluginValues(g, f) };
            }).filter(function (g) { return g.items.length; });
            hit.fieldMatches[field] = countItems(groups);
            if (groups.length) hit.recipe[field] = groups;
            return;
          }
          var values = pluginValues(root, f);
          hit.fieldMatches[field] = values.length;
          if (values.length) hit.recipe[field] = f.multiple ? values : values[0];
        });
      }
      hits[name] = hit;
    });
    return hits;
  }

  // ---- stage: Readability + heading heuristic --------------------------------------------------
  function headingKind(text) {
    var t = norm(text).toLocaleLowerCase('pl').replace(/[:：]\s*$/, '');
    if (!t || t.length > CONFIG.maxHeadingLength) return null;
    var kinds = ['ingredients', 'instructions', 'notes'];
    for (var k = 0; k < kinds.length; k++) {
      var words = CONFIG.headings[kinds[k]];
      for (var w = 0; w < words.length; w++) {
        if (t.indexOf(words[w]) !== -1) return kinds[k];
      }
    }
    return null;
  }
  function isHeadingLike(el) {
    if (/^H[1-6]$/.test(el.tagName)) return true;
    // <p><strong>Składniki</strong></p> style pseudo-headings.
    if (el.tagName === 'P' && el.children.length === 1 && /^(STRONG|B)$/.test(el.children[0].tagName)) {
      return norm(el.textContent) === norm(el.children[0].textContent);
    }
    return false;
  }
  // h1..h6 → 1..6, pseudo-headings → 7 (lowest).
  function headingLevel(el) {
    return /^H[1-6]$/.test(el.tagName) ? Number(el.tagName.charAt(1)) : 7;
  }
  function labelText(el) {
    return norm(el.textContent).replace(/[:：]\s*$/, '');
  }
  // A short label directly followed by a list ("Ciasto" + <ul>) starts a named group inside a section.
  function isSubheading(el) {
    if (!/^(P|DIV|SPAN|STRONG|B|H[2-6])$/.test(el.tagName)) return false;
    if (el.querySelector('li, ul, ol')) return false;
    var t = labelText(el);
    if (!t || t.length > CONFIG.maxSubheadingLength || /[.!?]$/.test(t)) return false;
    var next = el.nextElementSibling;
    return !!next && (next.tagName === 'UL' || next.tagName === 'OL');
  }
  // Ends the current section: another known section heading, or any other heading that is not a sub-heading
  // of lower rank than the section heading.
  function isSectionBoundary(el, sectionLevel) {
    if (!isHeadingLike(el)) return false;
    if (headingKind(el.textContent)) return true;
    var level = headingLevel(el);
    var lower = level > sectionLevel || (level === 7 && sectionLevel === 7);
    return !(lower && isSubheading(el));
  }
  // A div holding only text and inline elements (no nested blocks, lists or controls).
  function isTextLeaf(el) {
    for (var i = 0; i < el.children.length; i++) {
      if (!/^(SPAN|STRONG|B|EM|I|A|SUP|SUB|BR|CODE|MARK|SMALL)$/.test(el.children[i].tagName)) return false;
    }
    return true;
  }
  function addItem(groups, text) {
    if (!groups.length) groups.push({ name: null, items: [] });
    groups[groups.length - 1].items.push(text);
  }
  // Walks `el` in document order, appending list items / paragraphs to the last group and starting a new group at
  // each sub-heading. Returns true when the section ended inside `el`.
  function collectItems(el, groups, sectionLevel) {
    if (isSectionBoundary(el, sectionLevel)) return true;
    if (isSubheading(el)) {
      groups.push({ name: labelText(el), items: [] });
      return false;
    }
    var tag = el.tagName;
    if (tag === 'UL' || tag === 'OL') {
      queryAll(el, 'li').forEach(function (li) {
        if (li.querySelector('li')) return; // nested lists: keep only the innermost li
        var t = norm(li.textContent);
        if (t) addItem(groups, t);
      });
    } else if (tag === 'P') {
      var t = norm(el.textContent);
      if (t) addItem(groups, t);
    } else if (tag === 'DIV' && isTextLeaf(el)) {
      // Div-only layouts (Claude's recipe widget: one div of text per step).
      var d = norm(el.textContent);
      if (d) addItem(groups, d);
    } else if (/^(DIV|SECTION|ARTICLE|FIELDSET)$/.test(tag)) {
      for (var i = 0; i < el.children.length; i++) {
        if (collectItems(el.children[i], groups, sectionLevel)) return true;
      }
    }
    return false;
  }
  function countItems(groups) {
    return groups.reduce(function (n, g) { return n + g.items.length; }, 0);
  }
  // Groups ({name, items}) following a section heading; name is null for items before the first sub-heading.
  function groupsAfter(heading) {
    var groups = [];
    var level = headingLevel(heading);
    var node = heading;
    // The heading may be wrapped in a div; climb up to two levels when it has no siblings.
    for (var climb = 0; climb < 3; climb++) {
      for (var sib = node.nextElementSibling; sib; sib = sib.nextElementSibling) {
        if (collectItems(sib, groups, level)) break;
      }
      if (countItems(groups) || !node.parentElement || node.parentElement === document.body) break;
      groups = [];
      node = node.parentElement;
    }
    return groups.filter(function (g) { return g.items.length; });
  }
  // Moves the first "12 porcji" / "4 servings" line out of the ingredients.
  function takeServings(groups, out) {
    for (var g = 0; g < groups.length; g++) {
      for (var i = 0; i < groups[g].items.length; i++) {
        if (CONFIG.servingsLine.test(groups[g].items[i])) {
          out.servings = groups[g].items.splice(i, 1)[0];
          return;
        }
      }
    }
  }
  // Scans headings under `root`; fills only the kinds listed in `wanted` that are still empty in `out`.
  function scanHeadings(root, out, wanted) {
    queryAll(root, 'h1,h2,h3,h4,h5,h6,p').forEach(function (el) {
      if (!isHeadingLike(el)) return;
      var kind = headingKind(el.textContent);
      if (!kind || wanted.indexOf(kind) === -1) return;
      var groups = groupsAfter(el);
      // "Składniki na ciasto:" is a section heading and a group name at once; "Sposób przygotowania" or
      // "Przygotowanie krok po kroku" are not.
      var label = labelText(el);
      if (groups.length && groups[0].name === null && label.length <= CONFIG.maxSubheadingLength
          && CONFIG.groupHeading.test(label)) {
        groups[0].name = label;
      }
      if (kind === 'ingredients' && !out.servings) takeServings(groups, out);
      groups = groups.filter(function (g) { return g.items.length; });
      if (!countItems(groups)) return;
      if (kind === 'notes') {
        groups.forEach(function (g) { g.items.forEach(function (it) { out.notes.push(it); }); });
      } else {
        groups.forEach(function (g) { out[kind].push(g); });
      }
    });
  }
  // "1. Zetrzyj ziemniaki" + "Obierz ziemniaki…" (title and text as separate paragraphs, ChatGPT) →
  // "Zetrzyj ziemniaki: Obierz ziemniaki…", the same shape as steps that carry an inline bold label. Only in an
  // alternating title / text pattern: a run of short items is a list (layer order of a cake), not titles.
  function isStepTitle(text) {
    return text.length <= CONFIG.maxSubheadingLength && !/[.!?;,]$/.test(text);
  }
  function mergeStepTitles(groups) {
    groups.forEach(function (g) {
      var merged = [];
      for (var i = 0; i < g.items.length; i++) {
        var cur = g.items[i], next = g.items[i + 1];
        var prev = g.items[i - 1];
        if (next !== undefined && isStepTitle(cur) && !isStepTitle(next) && next.length > cur.length
            && (prev === undefined || !isStepTitle(prev))) {
          merged.push(cur.replace(/^\d+[.)]\s*/, '').replace(/[:：]\s*$/, '') + ': ' + next);
          i++;
        } else {
          merged.push(cur);
        }
      }
      g.items = merged;
    });
  }
  // The recipe's own heading: the nearest heading before the ingredients (or steps) heading that ranks above it.
  // Page titles of client-rendered apps are generic ("Claude", og:title "Check out this chat").
  function recipeHeading() {
    var headings = queryAll(document.body, 'h1,h2,h3,h4,h5,h6,p').filter(isHeadingLike);
    var at = -1;
    ['ingredients', 'instructions'].some(function (kind) {
      for (var i = 0; i < headings.length; i++) {
        if (headingKind(headings[i].textContent) === kind) { at = i; return true; }
      }
      return false;
    });
    if (at < 0) return null;
    var level = headingLevel(headings[at]);
    for (var j = at - 1; j >= 0; j--) {
      if (headingLevel(headings[j]) >= level || headingKind(headings[j].textContent)) continue;
      // Leading / trailing emoji and symbols ("🥔 Placki ziemniaczane").
      var t = norm(headings[j].textContent).replace(/^[^\p{L}\p{N}]+/u, '').replace(/[^\p{L}\p{N}).!?]+$/u, '');
      if (t && t.length <= 120) return t;
    }
    return null;
  }
  // Checklists (ingredients with checkboxes): a header like "Zaznacz składniki…" + "0/7" sits next to the rows.
  // In a container with at least two checkbox rows, rows without a checkbox are UI, not recipe content.
  function dropChecklistChrome(doc) {
    var rowCounts = new Map();
    queryAll(doc, '[role="checkbox"], input[type="checkbox"]').forEach(function (cb) {
      var row = cb.parentElement;
      var list = row && row.parentElement;
      if (list) rowCounts.set(list, (rowCounts.get(list) || 0) + 1);
    });
    rowCounts.forEach(function (count, list) {
      if (count < 2) return;
      Array.prototype.slice.call(list.children).forEach(function (child) {
        if (!child.querySelector('[role="checkbox"], input[type="checkbox"]')) list.removeChild(child);
      });
    });
  }
  function cleanedClone() {
    var doc = document.cloneNode(true);
    dropChecklistChrome(doc);
    return doc;
  }
  function extractHeuristic(errors, timings) {
    // ingredients / instructions: [{name, items}]; notes: flat strings; servings: the matched line or null.
    var out = { articleTitle: null, recipeHeading: null, usedBodyFallback: false, servings: null, ingredients: [],
                instructions: [], notes: [] };
    var article = null;
    var t0 = Date.now();
    try {
      if (!fromText) article = new Readability(cleanedClone()).parse();
    } catch (e) {
      errors.push('readability: ' + e.message);
    }
    timings.readability = Date.now() - t0;

    var root = null;
    if (article && article.content) {
      out.articleTitle = norm(article.title) || null;
      try {
        root = new DOMParser().parseFromString('<div>' + article.content + '</div>', 'text/html').body;
      } catch (e) {
        // Pages with Trusted Types (Gemini) block parsing HTML strings; Readability itself may or may not get
        // through, depending on its code path.
        errors.push('readability content: ' + e.message);
      }
    }
    if (!root) {
      out.usedBodyFallback = true;
      root = cleanedClone().body;
    }
    scanHeadings(root, out, ['ingredients', 'instructions', 'notes']);
    // Readability keeps the "main article", which on recipe sites is sometimes comments or a text block that
    // excludes the recipe card. If a part is still missing, scan the whole live document for it.
    if (!out.usedBodyFallback) {
      // Notes too: Readability tends to drop a short notes section (Claude's recipe widget).
      var missing = ['ingredients', 'instructions', 'notes'].filter(function (k) { return !out[k].length; });
      if (missing.length) {
        if (missing.indexOf('ingredients') !== -1 || missing.indexOf('instructions') !== -1) {
          out.usedBodyFallback = true;
        }
        scanHeadings(cleanedClone().body, out, missing);
      }
    }
    mergeStepTitles(out.instructions);
    out.recipeHeading = recipeHeading();
    return { heuristic: out, article: article };
  }

  // ---- plain text → DOM -------------------------------------------------------------------------
  // Shared text (selection → Share from AI chat apps) is flattened: no markdown, headings are plain lines. The lines
  // are turned into headings and lists so the heading heuristic above can run on them unchanged:
  //   h2 – a section line ("1. Składniki", "Przygotowanie") or a numbered short line not followed by another
  //        numbered line ("4. Dekoracja i chłodzenie"; "1. … / 2. …" in a row are steps),
  //   h3 – a label ("Biszkopt kokosowy:", "Krok 1: Biszkopt"),
  //   h1 – the first line when it looks like a title, everything else – list items.
  var NUMBERED = /^\d+[.)]\s+\S/;
  function isShortLabel(line) {
    return line.length <= CONFIG.maxHeadingLength && !/[.!?;,]$/.test(line);
  }
  function textLineKind(lines, i) {
    var line = lines[i];
    if (!isShortLabel(line)) return 'item';
    // A step can name a section word ("1. Wszystkie składniki zagniatamy…"): section lines are short.
    if (line.length <= CONFIG.maxSubheadingLength && headingKind(line)) return 'h2';
    if (NUMBERED.test(line)) {
      for (var j = i + 1; j < lines.length; j++) {
        if (lines[j]) return NUMBERED.test(lines[j]) ? 'item' : 'h2';
      }
      return 'item';
    }
    if (/[:：]$/.test(line) || /^(krok|etap|step)\s*\d+\b/i.test(line)) return 'h3';
    return 'item';
  }
  // Set by loadText: the DOM is ours, Readability has nothing to clean (and drops headings of tiny documents).
  var fromText = false;
  function loadText(text) {
    fromText = true;
    var body = document.body;
    while (body.firstChild) body.removeChild(body.firstChild);
    var lines = String(text || '').split(/\r?\n/).map(norm);
    var list = null;
    var first = true;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (!line) { list = null; continue; }
      var kind = textLineKind(lines, i);
      if (first && kind === 'item' && isShortLabel(line) && line.length <= 80) kind = 'h1';
      first = false;
      if (kind === 'item') {
        if (!list) list = body.appendChild(document.createElement('ul'));
        list.appendChild(document.createElement('li')).textContent = line.replace(/^\d+[.)]\s+/, '');
      } else {
        list = null;
        body.appendChild(document.createElement(kind)).textContent = line;
      }
    }
  }

  // ---- bot challenge -----------------------------------------------------------------------------
  // Interstitial pages that replace themselves with the real page once the check passes. Cloudflare defines
  // window._cf_chl_opt only on its challenge page (its bot-management script on normal pages does not).
  function detectBotChallenge() {
    if (typeof window._cf_chl_opt !== 'undefined') return 'cloudflare';
    if (/^just a moment/i.test(norm(document.title))) return 'cloudflare';
    return null;
  }

  // ---- entry point -----------------------------------------------------------------------------
  function run() {
    var errors = [];
    var timings = {};
    function timed(name, fn, fallback) {
      var t0 = Date.now();
      try { return fn(); }
      catch (e) { errors.push(name + ': ' + (e && e.message ? e.message : e)); return fallback; }
      finally { timings[name] = Date.now() - t0; }
    }

    var result = {
      title: norm(document.title) || null,
      ogImage: meta('meta[property="og:image"]', 'content'),
      siteName: meta('meta[property="og:site_name"]', 'content'),
      canonicalUrl: (document.querySelector('link[rel="canonical"]') || {}).href || null,
      finalUrl: location.href,
      lang: document.documentElement.lang || meta('meta[http-equiv="content-language"]', 'content') || null,
      botChallenge: detectBotChallenge(),
      // "hidden" in background mode (WebView not attached to a window): pages may lazy-load or throttle then.
      visibility: document.visibilityState || null
    };
    result.jsonLd = timed('jsonLd', function () { return extractJsonLd(errors); }, []);
    result.microdata = timed('microdata', extractMicrodata, []);
    result.pluginHits = timed('pluginHits', extractPlugins, {});
    var h = timed('heuristic', function () { return extractHeuristic(errors, timings); },
                  { heuristic: {}, article: null });
    result.heuristic = h.heuristic;
    result.readableText = h.article && h.article.textContent
      ? h.article.textContent.replace(/[ \t ]+/g, ' ').replace(/\n\s*\n+/g, '\n\n').trim()
      : null;
    result.timingsMs = timings;
    result.errors = errors;
    return result;
  }

  global.RecipeExtractor = { run: run, loadText: loadText, CONFIG: CONFIG };
})(window);
