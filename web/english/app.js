/* 诺诺英语学习屋 · 前端逻辑
 *
 * 2026-10-23 重写：从「硬编码 6 个单词 + 8 个单元标题 + 3 道题」改成**全部来自后端课程数据**
 * （/api/v1/english/curriculum，源头是教材 PDF 的逐页抽取 + 官方生词表）。
 *
 * 三条设计约束：
 *  1. 课程数据拿不到时**不能白屏**：给一句明确提示 + 保留最小可用的兜底内容。
 *  2. 进度存在 localStorage，但**按单元做键**（`u1:honest`）。不分单元的话，
 *     换个单元会看到上一个单元的「已掌握」，孩子会以为记错了。
 *  3. 发音优先走服务端 Azure（教材那套英式音），失败才退回浏览器 speechSynthesis
 *     —— 后者在安卓平板上常常没有英式音库，听起来是美音甚至机器腔。
 */
const STORE_KEY = 'nuonuoState';
const FALLBACK_UNITS = [{ unit: 1, title: 'Hello!', words: [], quiz: [], sentences: [], passage: { lines: [] } }];

let CURR = { units: [] };
let unitNo = 1;
let wordIndex = 0, quizIndex = 0, answered = false, sentenceIndex = 0;
let currentAudio = null;

const state = (() => {
  let s = {};
  try { s = JSON.parse(localStorage.getItem(STORE_KEY) || '{}'); } catch (e) { s = {}; }
  for (const k of ['tasks', 'known', 'hard', 'mistakes', 'seen', 'speakScores'])
    if (!Array.isArray(s[k])) s[k] = [];
  if (typeof s.repeat !== 'number') s.repeat = 0;
  if (typeof s.unit !== 'number') s.unit = 1;
  // 学情同步（与平板/家长手机同一份）：
  //   quiz    答题累计（网页原来根本没存答题总数，正确率无从谈起）
  //   synced  「已经上报到哪」的指针 —— 服务端做加法，报过的不许再报
  if (!s.quiz || typeof s.quiz !== 'object') s.quiz = { total: 0, right: 0 };
  if (!s.synced || typeof s.synced !== 'object') {
    s.synced = { seen: 0, known: 0, quizTotal: 0, quizRight: 0, repeat: 0 };
  }
  return s;
})();

const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

function save() {
  localStorage.setItem(STORE_KEY, JSON.stringify(state));
  updateDashboard();
  queueSync();
}

// ---------------------------------------------------------------- 学情同步
//
// 服务端是**加法**：客户端报增量。所以用 synced 指针记「上报到哪了」，
// flush 时只报差值 —— 页面刷新、换设备都不会双计。
let syncTimer = null;
function queueSync() { clearTimeout(syncTimer); syncTimer = setTimeout(syncProgress, 2500); }

async function syncProgress() {
  const sy = state.synced;
  const seen = state.seen.length;
  const known = state.known.length;
  const quizTotal = state.quiz.total, quizRight = state.quiz.right;
  // 跟读分数与 repeat 平行增长，从「已上报数」切片就是新增的几次
  const newScores = state.speakScores.slice(sy.repeat || 0);
  const delta = {
    unit: state.unit || 1,
    wordsSeen: Math.max(0, seen - (sy.seen || 0)),
    wordsKnown: Math.max(0, known - (sy.known || 0)),
    quizTotal: Math.max(0, quizTotal - (sy.quizTotal || 0)),
    quizCorrect: Math.max(0, quizRight - (sy.quizRight || 0)),
  };
  if (newScores.length) {
    delta.speakCount = newScores.length;
    delta.speakScore = +(newScores.reduce((a, b) => a + b, 0) / newScores.length).toFixed(1);
  }
  if (!(delta.wordsSeen || delta.wordsKnown || delta.quizTotal || delta.quizCorrect || delta.speakCount)) return;
  try {
    await api('/api/v1/english/progress', delta);
    state.synced = { seen, known, quizTotal, quizRight, repeat: state.repeat };
    // 直接写 localStorage，不走 save() —— 走 save() 会再触发一次 queueSync
    localStorage.setItem(STORE_KEY, JSON.stringify(state));
  } catch (e) { /* 上报失败就留着下次再试；学情丢一点不致命 */ }
}

function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.classList.add('show');
  setTimeout(() => el.classList.remove('show'), 1800);
}

function navigate(id) {
  $$('.view').forEach(v => v.classList.toggle('active', v.id === id));
  $$('.nav-item').forEach(v => v.classList.toggle('active', v.dataset.view === id));
  const titles = {
    today: '下午好，诺诺 👋', course: '同步课程', words: '单词卡片',
    quiz: '句型挑战', speaking: 'AI口语教室', mistakes: '错题本', report: '学习报告'
  };
  $('#pageTitle').textContent = titles[id] || '诺诺英语';
  // 地址栏带上 #words 这种锚点：刷新不掉页、家长也能直接把某一页发给别人。
  // 用 replaceState 而不是 pushState —— 不然每点一次导航都要按好几次返回才能退出。
  if (location.hash !== '#' + id) history.replaceState(null, '', '#' + id);
  window.scrollTo({ top: 0, behavior: 'smooth' });
  $('.sidebar').classList.remove('open');
}

// ---------------------------------------------------------------- 课程数据
async function api(path, body) {
  const r = await fetch(path, {
    method: body ? 'POST' : 'GET',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined
  });
  const j = await r.json().catch(() => ({ error: '服务器返回格式错误' }));
  if (!r.ok) throw new Error(j.error || `请求失败 (${r.status})`);
  return j;
}

async function loadCurriculum() {
  try {
    const data = await api('/api/v1/english/curriculum');
    CURR = data && data.units && data.units.length ? data : { units: FALLBACK_UNITS };
    if (data.warning) toast(data.warning);
  } catch (e) {
    // 课程接口挂了不等于整页不能看：给兜底 + 明确说明，别让孩子面对白屏。
    CURR = { units: FALLBACK_UNITS };
    toast('课程数据读取失败，先显示基础内容：' + e.message);
  }
  const known = CURR.units.find(u => u.unit === state.unit);
  unitNo = known ? known.unit : CURR.units[0].unit;
}

const currentUnit = () => CURR.units.find(u => u.unit === unitNo) || CURR.units[0];
const unitWords = () => {
  const ws = currentUnit().words || [];
  // 核心词排前面：孩子时间有限，先过教材标注的重点（附录里不带 * 的那些）
  return [...ws.filter(w => w.core), ...ws.filter(w => !w.core)];
};
const keyOf = (kind, v) => `u${unitNo}:${v}`;

function setUnit(n) {
  unitNo = n;
  state.unit = n;
  wordIndex = 0; quizIndex = 0; sentenceIndex = 0;
  save();
  renderAll();
  // 音频下拉跟着跳到这个单元的第 1 课时（只换源不自动播 —— 自己点开页面就出声很吓人）
  const sel = $('#audioTrack');
  if (sel && sel.dataset.ready === '1') {
    const idx = (n - 1) * 5;
    sel.value = String(idx);
    const audio = $('#courseAudio');
    if (audio) audio.src = `audio/track-${String(idx + 1).padStart(2, '0')}.mp3`;
  }
  toast(`切到 Unit ${n} · ${currentUnit().title}`);
}

function renderUnitPills(container) {
  if (!container) return;
  container.innerHTML = CURR.units.map(u => {
    const active = u.unit === unitNo;
    const badge = u.status === 'ready' ? '' : (u.status === 'partial' ? ' · 部分' : ' · 待录入');
    return `<button class="unit-pill ${active ? 'active' : ''}" data-unit="${u.unit}">
      <small>UNIT ${u.unit}${badge}</small><strong>${u.title}${u.titleZh ? ' · ' + u.titleZh : ''}</strong></button>`;
  }).join('');
  $$('[data-unit]').forEach(b => {
    if (b.closest('.unit-list') === container) b.onclick = () => setUnit(Number(b.dataset.unit));
  });
}

// ---------------------------------------------------------------- 各视图渲染
function renderHero() {
  const u = currentUnit();
  $('#heroUnit').textContent = `UNIT ${u.unit} · ${(u.title || '').toUpperCase()}`;
  $('#heroDesc').textContent = u.bigQuestion
    ? `Big question：${u.bigQuestion}`
    : '预计 15 分钟完成，先热身，再进入故事。';
  const ws = unitWords();
  $('#taskWordsDesc').textContent = ws.length
    ? `学习 ${ws.slice(0, 3).map(w => w.en).join('、')} 等 ${ws.length} 个词`
    : '本单元单词还没录入';
  $('#taskStoryDesc').textContent = u.passage && u.passage.title
    ? `课文《${u.passage.title}》`
    : '本单元课文还没录入';
  $('#taskQuizDesc').textContent = (u.quiz || []).length
    ? `${u.quiz.length} 道词汇与句型选择`
    : '本单元练习还没生成';
  $('#courseHint').textContent = `${CURR.units.filter(x => x.status === 'ready').length}/${CURR.units.length} 个单元已录入`;
}

function renderLesson() {
  const u = currentUnit();
  const p = u.passage || { lines: [] };
  $('#lessonEyebrow').textContent = `STORY TIME · UNIT ${u.unit}`;
  $('#lessonTitle').textContent = p.title || u.title || '';
  $('#lessonCopy').textContent = p.lines.length
    ? '点击任意一句听示范，然后跟着读。'
    : '这个单元的课文还没录入。';
  // 每行一个可点句子：整篇一起读会超过 TTS 800 字上限，而且孩子跟不上。
  $('#lessonDialogue').innerHTML = (p.lines || []).slice(0, 6).map((line, i) => {
    const text = cleanLine(line);
    return text ? `<p data-say="${escapeAttr(text)}"><b>${i + 1}.</b> ${escapeHtml(text)} <span class="say">🔊</span></p>` : '';
  }).join('');
  $$('[data-say]').forEach(el => el.onclick = () => playText(el.dataset.say));
  $('#bigQuestionText').innerHTML = (u.bigQuestion || '').replace(/\?/, '?<br />');
  $('#bigQuestionNote').textContent = u.titleZh ? `本单元主题：${u.titleZh}` : '';
}

function cleanLine(line) {
  // 教材分段带序号（"1 It was Bobby's turn."），念出来会多一个数字，所以只留正文。
  return String(line || '').replace(/^\s*\d+\s+/, '').trim();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function escapeAttr(s) { return escapeHtml(s); }

function renderWord() {
  const ws = unitWords();
  const u = currentUnit();
  $('#wordsEyebrow').textContent = `UNIT ${u.unit} · WORD BANK`;
  $('#wordsTitle').textContent = `${u.title} 的核心单词`;
  if (!ws.length) {
    $('#wordText').textContent = '暂无单词';
    $('#phonetic').textContent = '';
    $('#meaningText').textContent = '这个单元的单词还没录入';
    $('#exampleText').textContent = '';
    $('#tipText').textContent = '';
    $('#wordCounter').textContent = '0 / 0';
    return;
  }
  wordIndex = ((wordIndex % ws.length) + ws.length) % ws.length;
  const w = ws[wordIndex];
  $('#wordText').textContent = w.en;
  $('#phonetic').textContent = w.phonetic || '';
  $('#meaningText').textContent = w.zh || '（释义待补）';
  $('#exampleText').textContent = w.example || '';
  $('#tipText').textContent = w.tip || (w.core ? '教材核心词' : '拓展词');
  $('#wordCounter').textContent = `${wordIndex + 1} / ${ws.length}`;
  $('#flashcard').classList.remove('flipped');
  // 「翻到过」也是学情信号（与平板 markWordSeen 同款语义），按 key 去重
  const skey = keyOf('word', w.en.toLowerCase());
  if (!state.seen.includes(skey)) { state.seen.push(skey); save(); }
}

function moveWord(step) {
  wordIndex += step;
  renderWord();
}

function finishWord(known) {
  const ws = unitWords();
  if (!ws.length) return;
  const w = ws[wordIndex];
  const key = keyOf('word', w.en.toLowerCase());
  const bucket = known ? state.known : state.hard;
  if (!bucket.includes(key)) bucket.push(key);
  const other = known ? state.hard : state.known;
  const i = other.indexOf(key);
  if (i >= 0) other.splice(i, 1);   // 这一轮记住了就从「还不熟悉」里移出，别两边都留
  if (known) {
    const coreCount = ws.filter(x => x.core).length;
    const knowCore = ws.filter(x => x.core && state.known.includes(keyOf('word', x.en.toLowerCase()))).length;
    if (coreCount && knowCore >= coreCount) complete('words', '本单元核心词都记住啦！');
    else save();
  } else save();
  toast(known ? '记住啦，继续下一个！' : '已加入重点复习');
  moveWord(1);
}

function renderQuiz() {
  const qs = currentUnit().quiz || [];
  answered = false;
  $('#nextQuestion').disabled = true;
  if (!qs.length) {
    $('#quizStep').textContent = '本单元还没有练习题';
    $('#quizQuestion').textContent = '练习题还没生成';
    $('#quizOptions').innerHTML = '';
    $('#quizFeedback').textContent = '';
    $('#quizBar').style.width = '0%';
    return;
  }
  quizIndex = ((quizIndex % qs.length) + qs.length) % qs.length;
  const q = qs[quizIndex];
  $('#quizStep').textContent = `第 ${quizIndex + 1} 题 / 共 ${qs.length} 题`;
  $('#quizBar').style.width = `${(quizIndex + 1) / qs.length * 100}%`;
  $('#quizQuestion').textContent = q.q;
  $('#quizFeedback').textContent = '';
  $('#quizOptions').innerHTML = q.options.map((o, i) =>
    `<button class="quiz-option" data-answer="${i}"><b>${String.fromCharCode(65 + i)}.</b> ${escapeHtml(o)}</button>`).join('');
  $$('.quiz-option').forEach(b => b.onclick = () => answerQuiz(Number(b.dataset.answer)));
}

function answerQuiz(choice) {
  const qs = currentUnit().quiz || [];
  if (answered || !qs.length) return;
  answered = true;
  const q = qs[quizIndex];
  $$('.quiz-option').forEach((b, i) => {
    if (i === q.answer) b.classList.add('correct');
    if (i === choice && choice !== q.answer) b.classList.add('wrong');
  });
  // 网页原来根本没存答题总数 —— 家长看到的「正确率」只能来自平板端。
  // 学情要跨端同步，每一端都得把自己答的题记上。
  state.quiz.total += 1;
  if (choice === q.answer) state.quiz.right += 1;
  if (choice === q.answer) {
    $('#quizFeedback').textContent = '✓ 回答正确！' + (q.explain || '');
  } else {
    $('#quizFeedback').textContent = '再记一下：' + (q.explain || '');
    const key = `${unitNo}|${q.q}`;
    if (!state.mistakes.some(m => m.key === key)) {
      state.mistakes.push({ key, unit: unitNo, q: q.q, answer: q.options[q.answer], reason: q.explain || '' });
    }
  }
  $('#nextQuestion').disabled = false;
  save();
}

function renderMistakes() {
  const list = $('#mistakeList');
  if (!state.mistakes.length) {
    list.innerHTML = '<div class="empty-state"><h3>暂时没有错题 🎉</h3><p>完成「句型挑战」后，答错的题目会自动出现在这里。</p></div>';
    return;
  }
  list.innerHTML = state.mistakes.map((m, i) => `<article class="mistake-item"><span>${i + 1}</span>
    <div><h3>${escapeHtml(m.q)}</h3><p>Unit ${m.unit} · 正确答案：<b>${escapeHtml(m.answer)}</b>${m.reason ? ' · ' + escapeHtml(m.reason) : ''}</p></div>
    <button class="secondary" data-remove="${i}">已掌握</button></article>`).join('');
  $$('[data-remove]').forEach(b => b.onclick = () => {
    state.mistakes.splice(Number(b.dataset.remove), 1);
    save(); toast('已从错题本移除');
  });
}

function renderSpeaking() {
  const u = currentUnit();
  const sents = (u.sentences || []).filter(s => s.en);
  if (sents.length) {
    sentenceIndex = ((sentenceIndex % sents.length) + sents.length) % sents.length;
    $('#repeatText').textContent = sents[sentenceIndex].en;
  } else if ((u.passage || {}).lines && u.passage.lines.length) {
    $('#repeatText').textContent = cleanLine(u.passage.lines[0]);
  }
}

function renderReport() {
  const totalWords = CURR.units.reduce((n, u) => n + (u.words || []).length, 0);
  const knowCore = state.known.length;
  $('#knownStat').innerHTML = `${knowCore}<small> / ${totalWords} 个</small>`;
  $('#repeatStat').innerHTML = `${state.repeat}<small> 次</small>`;
  $('#taskStat').innerHTML = `${state.tasks.length}<small> 个</small>`;
  $('#weeklyScore').textContent = String(Math.min(99, 60 + Math.min(30, knowCore) + state.repeat * 2));
  $('#mistakeBadge').textContent = state.mistakes.length;

  // —— 服务端那份（含平板上学的）覆盖本地数字：这就是「三端同步」——
  //    失败时静默保留本地数字（离线也有东西看，只是少了别的设备上的）。
  api('/api/v1/english/progress?days=7').then(s => {
    const t = s.totals || {};
    if (t.wordsKnown != null) $('#knownStat').innerHTML = `${t.wordsKnown}<small> / ${totalWords} 个</small>`;
    if (t.speakCount != null) $('#repeatStat').innerHTML = `${t.speakCount}<small> 次</small>`;
    if (t.quizAccuracy != null) {
      $('#weeklyScore').textContent =
        String(Math.min(99, Math.round(60 + t.quizAccuracy * 25 + t.speakAvg * 0.14)));
    }
    if (Array.isArray(s.daily) && s.daily.length) {
      // 每日学习时间的柱子终于不是写死的了
      $('#barChart').innerHTML = s.daily.map(d => {
        const amount = (d.wordsSeen || 0) + (d.quizTotal || 0) + (d.speakCount || 0);
        return `<div class="bar" style="height:${Math.max(5, Math.min(120, amount * 8))}px"><span>${d.day.slice(5).replace('-', '/')}</span></div>`;
      }).join('');
    }
    if (s.range) $('#reportRange').textContent = `${s.range.from} 至 ${s.range.to}`;
    queueSync();
  }).catch(() => { /* 离线：保留本地数字 */ });
}

function updateDashboard() {
  const done = state.tasks.filter(t => t.startsWith(`u${unitNo}:`)).length;
  $('#doneCount').textContent = done;
  const pct = Math.round(done / 4 * 100);
  $('#progressRing').textContent = `${pct}%`;
  $('#progressRing').style.background = `conic-gradient(var(--green) ${pct}%,#e2e2da 0)`;
  $$('.task-card').forEach(c => c.classList.toggle('done', state.tasks.includes(`${keyOf('task', c.dataset.task)}`)));
  renderReport();
  renderMistakes();
}

function complete(task, msg) {
  const key = keyOf('task', task);
  if (!state.tasks.includes(key)) state.tasks.push(key);
  save();
  toast(msg);
}

// ---------------------------------------------------------------- 发音
async function playText(text) {
  if (!text) return;
  let r;
  try {
    r = await api('/api/tts', { text });
  } catch (e) {
    // 只有「合成请求本身失败」（Azure 没配 key / 网络不通）才退回设备音。
    // ⚠️ 别把 play() 的失败也塞进来：那多半只是浏览器拦了自动播放，
    //    这时候换一种声音再播一次，听起来像点了两下、两个人在念。
    speakFallback(text);
    return;
  }
  if (currentAudio) { currentAudio.pause(); currentAudio = null; }
  currentAudio = new Audio(r.url);
  try {
    await currentAudio.play();
  } catch (e) {
    toast('浏览器拦截了自动播放，先点一下页面任意位置');
  }
}

function speakFallback(text) {
  if (!('speechSynthesis' in window)) { toast('当前浏览器不支持语音播放'); return; }
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'en-GB';
  u.rate = 0.82;
  speechSynthesis.speak(u);
}

// ---------------------------------------------------------------- 录音 / 评测 / 对话
function makeRecorder(button, label, onAudio) {
  let recorder = null, chunks = [];
  button.onclick = async () => {
    if (recorder && recorder.state === 'recording') { recorder.stop(); return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 } });
      const type = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
      recorder = new MediaRecorder(stream, { mimeType: type });
      chunks = [];
      recorder.ondataavailable = e => { if (e.data.size) chunks.push(e.data); };
      recorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        button.classList.remove('recording');
        label.textContent = '正在处理录音…';
        const blob = new Blob(chunks, { type });
        const reader = new FileReader();
        reader.onload = () => onAudio(reader.result).catch(e => { toast(e.message); label.textContent = '点击重新录音'; });
        reader.readAsDataURL(blob);
      };
      recorder.start();
      button.classList.add('recording');
      label.textContent = '正在录音，再次点击结束';
    } catch (e) {
      toast(e.message || '无法使用麦克风');
    }
  };
}

function renderScore(result) {
  const score = n => Number.isFinite(Number(n)) ? Math.round(Number(n)) : 0;
  const words = result?.Words || result?.words || [];
  $('#scoreResult').classList.remove('empty');
  $('#scoreResult').innerHTML = `<div class="score-overview">
    <div class="score-metric"><strong>${score(result?.SuggestedScore)}</strong><span>综合得分</span></div>
    <div class="score-metric"><strong>${score(result?.PronAccuracy)}</strong><span>准确度</span></div>
    <div class="score-metric"><strong>${score(result?.PronFluency)}</strong><span>流利度</span></div>
    <div class="score-metric"><strong>${score(result?.PronCompletion)}</strong><span>完整度</span></div></div>
    <div class="word-scores">${words.length ? words.map(w => {
      const s = score(w.PronAccuracy);
      return `<span class="word-score ${s < 70 ? 'weak' : ''}">${escapeHtml(w.Word || w.ReferenceWord || 'word')} · ${s}</span>`;
    }).join('') : '<span class="muted">已获得整体评分；逐词明细将在服务返回时显示。</span>'}</div>`;
}

const chatHistory = [{ role: 'assistant', content: 'Hello, Nono! Why is honesty important?' }];

function addChat(role, text) {
  chatHistory.push({ role, content: text });
  const item = document.createElement('div');
  item.className = `chat-bubble ${role}`;
  const small = document.createElement('small');
  small.textContent = role === 'user' ? '诺诺' : 'AI Tutor';
  const p = document.createElement('p');
  p.textContent = text;          // 用 textContent 而不是 innerHTML：AI 回复里可能有 < >
  item.appendChild(small); item.appendChild(p);
  $('#chatMessages').appendChild(item);
  $('#chatMessages').scrollTop = $('#chatMessages').scrollHeight;
}

async function checkServices() {
  try {
    const h = await api('/api/health');
    const ready = Object.values(h.services).filter(Boolean).length;
    $('#serviceStatus').textContent = ready === 4 ? '四个云服务均已连接' : `已连接 ${ready}/4 · 缺少密钥配置`;
    $('#serviceStatus').className = `service-status ${ready === 4 ? 'ready' : 'partial'}`;
  } catch {
    $('#serviceStatus').textContent = '本地服务未连接';
    $('#serviceStatus').className = 'service-status partial';
  }
}

/* 课课练配套音频。
 *
 * 三年级版（2026-10 改造）：六年级的 40 段课课练音频已随教材更换移除。
 * 找到三年级配套音频后：按 track-NN.mp3 命名放回 web/english/audio/，
 * 恢复下面的 length 与每单元课时数，并且**必须用服务端 ASR 逐段重验单元映射**
 * （原六年级映射是 track-01 = "Unit 1, Period 1"、步长 5 —— 换音频包不能沿用）。
 */
const AUDIO_TRACK_COUNT = 0; // 0 = 音频库暂不可用（界面显示「整理中」）

function renderAudio() {
  const sel = $('#audioTrack');
  if (!sel || sel.dataset.ready === '1') return;
  if (!AUDIO_TRACK_COUNT) {
    sel.innerHTML = '<option>配套音频整理中，先用「播放示范」听 TTS</option>';
    sel.disabled = true;
    return;
  }
  const per = 5;
  const tracks = Array.from({ length: AUDIO_TRACK_COUNT }, (_, i) => {
    const n = i + 1, u = Math.ceil(n / per), p = ((n - 1) % per) + 1;
    const unit = CURR.units.find(x => x.unit === u);
    const name = p === per ? `Unit ${u} · 单元检测` : `Unit ${u} · Period ${p}`;
    return {
      file: `audio/track-${String(n).padStart(2, '0')}.mp3`,
      label: unit && unit.title ? `${name} · ${unit.title}` : name
    };
  });
  sel.innerHTML = tracks.map((t, i) => `<option value="${i}">${String(i + 1).padStart(2, '0')} · ${t.label}</option>`).join('');
  sel.dataset.ready = '1';
  const audio = $('#courseAudio');
  if (audio) {
    audio.src = tracks[0].file;
    sel.onchange = e => { audio.src = tracks[Number(e.target.value)].file; audio.play().catch(() => {}); };
  }
}

// ---------------------------------------------------------------- 渲染总入口
/* 版本号：显示在左侧栏底部。作用是**一眼分辨「你看到的是哪一版」** ——
 * 2026-10-23 的故障就是新 HTML 配旧 JS，光看界面分不清，只能靠这个戳。 */
const BUILD = '20261023-2';
let renderErrors = [];

function safe(label, fn) {
  // 每个区块单独 try：一块炸了不能让整页跟着空白（真实故障就是这么来的）
  try { fn(); } catch (e) { renderErrors.push(`${label}：${e && e.message ? e.message : e}`); console.error('[render]', label, e); }
}

function showRenderError() {
  const old = $('#renderError');
  if (!renderErrors.length) { if (old) old.remove(); return; }
  const box = old || (() => {
    const d = document.createElement('div');
    d.id = 'renderError';
    d.style.cssText = 'margin:16px 0;padding:12px 16px;border-radius:12px;background:#fff0eb;color:#a94f38;font-size:13px;line-height:1.6';
    document.querySelector('main').prepend(d);
    return d;
  })();
  box.textContent = '页面有部分内容没渲染出来（' + renderErrors.join('；') +
    '）。先按 Ctrl+F5 强制刷新；还不行就把这句话告诉家长。';
}

function renderAll() {
  renderErrors = [];
  safe('单元列表', () => { renderUnitPills($('#unitList')); renderUnitPills($('#wordsUnitPills')); });
  safe('今日学习', renderHero);
  safe('课文', renderLesson);
  safe('单词卡', renderWord);
  safe('练习题', renderQuiz);
  safe('跟读', renderSpeaking);
  safe('音频', renderAudio);
  safe('学习统计', updateDashboard);
  const stamp = $('#buildVer');
  if (stamp) stamp.textContent = 'v' + BUILD;
  showRenderError();
}

document.addEventListener('DOMContentLoaded', async () => {
  $('#dateLabel').textContent = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date());
  $('#barChart').innerHTML = [8, 12, 6, 15, 10, 17, 0].map((h, i) =>
    `<div class="bar" style="height:${Math.max(5, h * 8)}px;opacity:${h ? 1 : .25}"><span>${'一二三四五六日'[i]}</span></div>`).join('');

  $$('.nav-item').forEach(b => b.onclick = () => navigate(b.dataset.view));
  $$('[data-view-jump]').forEach(b => b.onclick = () => navigate(b.dataset.viewJump));
  $$('.task-card button').forEach(b => b.onclick = () => {
    const t = b.closest('.task-card').dataset.task;
    if (t === 'words' || t === 'review') navigate('words');
    if (t === 'story') navigate('course');
    if (t === 'quiz') navigate('quiz');
  });
  $('#startLearning').onclick = () => navigate('words');
  $('#menuButton').onclick = () => $('.sidebar').classList.toggle('open');

  $('#flashcard').onclick = e => { if (e.target.id !== 'speakWord') $('#flashcard').classList.toggle('flipped'); };
  $('#flashcard').onkeydown = e => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('#flashcard').classList.toggle('flipped'); }
  };
  $('#speakWord').onclick = e => {
    e.stopPropagation();
    const w = unitWords()[wordIndex];
    if (w) playText(w.en + '. ' + (w.example || ''));
  };
  $('#prevWord').onclick = () => moveWord(-1);
  $('#nextWord').onclick = () => moveWord(1);
  $('#knowWord').onclick = () => finishWord(true);
  $('#hardWord').onclick = () => finishWord(false);

  $('#speakStory').onclick = () => {
    const p = currentUnit().passage || {};
    const text = (p.lines || []).slice(0, 2).map(cleanLine).join(' ');
    playText(text || currentUnit().bigQuestion || '');
  };
  $('#finishStory').onclick = () => complete('story', '故事跟读完成！');

  $('#nextQuestion').onclick = () => {
    const qs = currentUnit().quiz || [];
    if (quizIndex < qs.length - 1) { quizIndex++; renderQuiz(); }
    else { complete('quiz', '句型挑战完成！'); quizIndex = 0; renderQuiz(); navigate('today'); }
  };

  $('#clearMistakes').onclick = () => { state.mistakes = []; save(); toast('错题本已整理好'); };

  $$('[data-speak-mode]').forEach(b => b.onclick = () => {
    $$('[data-speak-mode]').forEach(x => x.classList.toggle('active', x === b));
    $('#repeatPanel').classList.toggle('hidden', b.dataset.speakMode !== 'repeat');
    $('#chatPanel').classList.toggle('hidden', b.dataset.speakMode !== 'chat');
  });
  $('#playPrompt').onclick = () => playText($('#repeatText').textContent);
  $('#nextSentence').onclick = () => { sentenceIndex++; renderSpeaking(); };

  makeRecorder($('#repeatRecord'), $('#repeatRecordLabel'), async audio => {
    const r = await api('/api/evaluate', { text: $('#repeatText').textContent, audio });
    renderScore(r.result);
    state.repeat += 1;
    // 0 分通常是没录到声音（麦克风被占/太安静），不是一次学习行为
    if (r.result && r.result.SuggestedScore > 0) state.speakScores.push(r.result.SuggestedScore);
    $('#repeatRecordLabel').textContent = '评测完成，点击可再读一次';
    complete('story', '跟读评测完成！');
  });
  makeRecorder($('#chatRecord'), $('#chatRecordLabel'), async audio => {
    const transcript = await api('/api/transcribe', { audio });
    if (!transcript.text) throw new Error('没有识别到有效英语');
    addChat('user', transcript.text);
    $('#chatRecordLabel').textContent = 'AI正在思考…';
    const reply = await api('/api/chat', { messages: chatHistory });
    addChat('assistant', reply.text);
    $('#chatRecordLabel').textContent = '点击继续说英语';
    if (reply.audioUrl) new Audio(reply.audioUrl).play().catch(() => {});
  });

  await loadCurriculum();
  renderAll();
  // 深链接：#words / #course / #speaking … 直接落到对应页
  const deep = location.hash.slice(1);
  if (deep && $('#pageTitle') && document.getElementById(deep)) navigate(deep);
  checkServices();
});
