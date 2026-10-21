// CookAlong website interactions. No dependencies.
(() => {
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const finePointer = matchMedia("(pointer: fine)").matches;

  // Recipe photos: [file id, title, credit, source page]. Images are cropped squares of the originals.
  const DISHES = [
    ["masala-dosa", "Masala Dosa", "Marajozkee · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Masala_dosa_01.jpg"],
    ["chicken-biryani", "Chicken Biryani", "Vis M · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Chicken_Dum_Biryani_03.jpg"],
    ["pad-thai", "Pad Thai", "Tuxta · CC BY-SA 3.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:SamedFEB10_-_102.JPG"],
    ["paella", "Valencian Paella", "based.cooking · Public domain", "https://based.cooking/paella/"],
    ["tiramisu", "Tiramisù", "Javier Lastras · CC BY 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Dessert_Tiramisu.jpg"],
    ["shakshuka-i", "Shakshuka", "Chubbychef · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Shakshoka.jpg"],
    ["dal-makhani", "Dal Makhani", "Charles Haynes · CC BY-SA 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Dal_Makhani.jpg"],
    ["bulgogi", "Bulgogi", "brappy! · CC BY-SA 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Korean.food-Bulgogi-01.jpg"],
    ["ratatouille", "Ratatouille", "Marcus Guimarães · CC BY 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Ratatouille.jpg"],
    ["gulab-jamun", "Gulab Jamun", "Cabeza2000 · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Gulab_jamun_(Gibraltar,_November_2020).jpg"],
    ["pani-puri", "Pani Puri", "Yusuke Kawasaki · CC BY 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Golgappa_Pani_Puri_India.jpg"],
    ["miso-soup", "Miso Soup", "Ocdp · CC0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Miso_Soup_001.jpg"],
    ["paneer-tikka-masala", "Paneer Tikka Masala", "based.cooking · Public domain", "https://based.cooking/paneer-tikka-masala/"],
    ["idli", "Idli", "Nick Gray · CC BY-SA 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Idli1.jpg"],
    ["chicken-tikka-masala", "Chicken Tikka Masala", "Guilhem Vellut · CC BY 2.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Chicken_Tikka_Masala_@_Indian%27s_Food_@_Annecy_(38894403364).jpg"],
    ["kung-pao-chicken", "Kung Pao Chicken", "Steven G. Johnson · CC BY-SA 3.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Kung-pao-shanghai.jpg"],
    ["jalebi", "Jalebi", "PallaviKhale · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Jalebi_PK013.jpg"],
    ["french-crepes", "French Crêpes", "Kgbo · CC BY-SA 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Cr%C3%AApes_of_France_on_Brisbane,_Australia.jpg"],
    ["tom-yum-gai", "Tom Yum Gai", "Vee Satayamas · CC BY 4.0 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:%E0%B8%95%E0%B9%89%E0%B8%A1%E0%B8%A2%E0%B8%B3%E0%B8%97%E0%B8%B0%E0%B9%80%E0%B8%A5%E0%B8%99%E0%B9%89%E0%B8%B3%E0%B9%83%E0%B8%AA.jpg"],
    ["naan", "Naan", "OpenCage · CC BY-SA 2.5 · Wikimedia Commons", "https://commons.wikimedia.org/wiki/File:Naan_shiva.jpg"],
  ];

  // ---------- page load: hero word reveal ----------
  $$(".hero-title .word").forEach((w, i) => (w.style.transitionDelay = `${0.15 + i * 0.08}s`));
  requestAnimationFrame(() => document.body.classList.add("loaded"));

  // ---------- marquee + credits ----------
  const half = Math.ceil(DISHES.length / 2);
  $$(".marquee").forEach((row) => {
    const list = row.dataset.row === "0" ? DISHES.slice(0, half) : DISHES.slice(half);
    const html = list.map(([id, title, credit]) =>
      `<figure class="dish" title="${title} · photo: ${credit}"><img src="assets/food/${id}.jpg" alt="${title}" loading="lazy" width="240" height="240"><span>${title}</span></figure>`
    ).join("");
    row.innerHTML = reduced ? html : html + html; // doubled for a seamless loop
    if (!reduced) $$(".dish", row).slice(list.length).forEach((d) => d.setAttribute("aria-hidden", "true"));
  });
  $("#creditList").innerHTML = DISHES.map(([, title, credit, page]) =>
    `<li>${title}: <a href="${page}" target="_blank" rel="noopener">${credit}</a> (cropped)</li>`).join("");

  // ---------- nav, scroll progress, menu ----------
  const nav = $("#nav");
  const bar = $(".progress");
  const onScroll = () => {
    const y = scrollY;
    nav.classList.toggle("scrolled", y > 30);
    const max = document.documentElement.scrollHeight - innerHeight;
    bar.style.transform = `scaleX(${max > 0 ? y / max : 0})`;
  };
  addEventListener("scroll", onScroll, { passive: true });
  onScroll();
  const menuBtn = $(".menu-btn");
  menuBtn.addEventListener("click", () => {
    const open = nav.classList.toggle("open");
    menuBtn.setAttribute("aria-expanded", open);
  });
  $$(".nav-links a").forEach((a) => a.addEventListener("click", () => {
    nav.classList.remove("open");
    menuBtn.setAttribute("aria-expanded", "false");
  }));

  // active nav link
  const navIO = new IntersectionObserver((entries) => entries.forEach((e) => {
    if (e.isIntersecting) $$(".nav-links a").forEach((a) => a.classList.toggle("active", a.getAttribute("href") === "#" + e.target.id));
  }), { rootMargin: "-45% 0px -50% 0px" });
  $$(".nav-links a").forEach((a) => navIO.observe($(a.getAttribute("href"))));

  // ---------- reveal on scroll + counters ----------
  const counted = new WeakSet();
  const countUp = (el) => {
    if (counted.has(el)) return;
    counted.add(el);
    const target = parseFloat(el.dataset.count);
    const dec = parseInt(el.dataset.dec || "0", 10);
    if (reduced) { el.textContent = target.toFixed(dec); return; }
    const dur = 1600, t0 = performance.now();
    const tick = (t) => {
      const p = Math.min(1, (t - t0) / dur);
      const e = 1 - Math.pow(1 - p, 4);
      el.textContent = (target * e).toFixed(dec);
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  };
  const io = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return;
      e.target.classList.add("in");
      $$("[data-count]", e.target).forEach(countUp);
      if (e.target.dataset.count) countUp(e.target);
      io.unobserve(e.target);
    });
  }, { threshold: 0.15, rootMargin: "0px 0px -60px 0px" });
  $$(".reveal").forEach((el) => {
    // stagger siblings in the same parent
    const sibs = $$(":scope > .reveal", el.parentElement);
    if (sibs.length > 1 && !el.style.getPropertyValue("--d")) el.style.setProperty("--d", `${sibs.indexOf(el) * 0.08}s`);
    io.observe(el);
  });
  $$(".hero-meta [data-count]").forEach((el) => setTimeout(() => countUp(el), 900));

  // ---------- cursor glow, card spotlight, magnetic buttons, TV tilt ----------
  if (finePointer && !reduced) {
    const glow = $(".cursor-glow");
    let mx = -999, my = -999, gx = mx, gy = my;
    addEventListener("pointermove", (e) => { mx = e.clientX; my = e.clientY; }, { passive: true });
    const loop = () => {
      gx += (mx - gx) * 0.12; gy += (my - gy) * 0.12;
      glow.style.transform = `translate3d(${gx}px, ${gy}px, 0)`;
      requestAnimationFrame(loop);
    };
    loop();

    $$(".spot").forEach((card) => card.addEventListener("pointermove", (e) => {
      const r = card.getBoundingClientRect();
      card.style.setProperty("--mx", `${e.clientX - r.left}px`);
      card.style.setProperty("--my", `${e.clientY - r.top}px`);
    }));

    $$(".magnetic").forEach((b) => {
      b.addEventListener("pointermove", (e) => {
        const r = b.getBoundingClientRect();
        const x = e.clientX - r.left - r.width / 2, y = e.clientY - r.top - r.height / 2;
        b.style.transform = `translate(${x * 0.2}px, ${y * 0.3}px)`;
      });
      b.addEventListener("pointerleave", () => (b.style.transform = ""));
    });

    const tv = $("#heroTv"), hv = $(".hero-visual");
    hv.addEventListener("pointermove", (e) => {
      const r = hv.getBoundingClientRect();
      const x = (e.clientX - r.left) / r.width - 0.5, y = (e.clientY - r.top) / r.height - 0.5;
      tv.style.transform = `rotateY(${x * 14}deg) rotateX(${-y * 10}deg)`;
    });
    hv.addEventListener("pointerleave", () => (tv.style.transform = "rotateY(-8deg) rotateX(4deg)"));
    tv.style.transform = "rotateY(-8deg) rotateX(4deg)";
  }

  // ---------- hero timer chip ----------
  const heroTimer = $("#heroTimer"), ringFg = $(".fc-timer .ring-fg");
  const HERO_TOTAL = 540;
  let heroLeft = HERO_TOTAL;
  const fmt = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  setInterval(() => {
    heroLeft = heroLeft <= 0 ? HERO_TOTAL : heroLeft - 1;
    heroTimer.textContent = fmt(heroLeft);
    ringFg.style.strokeDashoffset = 119.4 * (1 - heroLeft / HERO_TOTAL);
  }, 1000);

  // ---------- how it works: sticky TV swaps screen with the active step ----------
  const steps = $$(".how-step");
  const shots = $$(".how-stage img");
  const dots = $$(".stage-dots i");
  steps.forEach((s) => {
    const src = shots.find((i) => i.dataset.shot === s.dataset.shot);
    const m = src.cloneNode();
    m.className = "shot-m";
    m.loading = "lazy";
    s.appendChild(m);
  });
  const stepIO = new IntersectionObserver((entries) => {
    entries.forEach((e) => {
      if (!e.isIntersecting) return;
      const key = e.target.dataset.shot;
      steps.forEach((s) => s.classList.toggle("active", s === e.target));
      shots.forEach((i) => i.classList.toggle("active", i.dataset.shot === key));
      dots.forEach((d, i) => d.classList.toggle("on", i === steps.indexOf(e.target)));
    });
  }, { rootMargin: "-45% 0px -45% 0px" });
  steps.forEach((s) => stepIO.observe(s));
  steps[0].classList.add("active");

  // ---------- interactive cooking mode ----------
  const RECIPE = [
    { e: "🫕", t: "Bring a large pot of well-salted water to a boil.", ings: ["4 L water", "1 tbsp salt"] },
    { e: "🍝", t: "Add the spaghetti and stir. Cook until just al dente, about 9 minutes.", ings: ["400 g spaghetti"], timer: ["Pasta", 540] },
    { e: "🍳", t: "Meanwhile, warm the olive oil, garlic and chili flakes in a wide pan over low heat. Don't let the garlic brown.", ings: ["4 tbsp olive oil", "4 cloves garlic", "½ tsp chili flakes"], timer: ["Garlic", 120] },
    { e: "🍋", t: "Toss the drained pasta into the pan with lemon zest, lemon juice and a splash of pasta water.", ings: ["1 lemon", "½ cup pasta water"] },
    { e: "🌿", t: "Finish with parsley and parmesan. Serve right away.", ings: ["Parsley", "Parmesan"] },
  ];
  let cur = 0;
  const elBars = $("#cookBars"), elInner = $(".cook-inner");
  elBars.innerHTML = RECIPE.map(() => "<i></i>").join("");
  $("#stepTotal").textContent = RECIPE.length;
  const prevBtn = $(".cook-arrow.prev"), nextBtn = $(".cook-arrow.next");
  const hint = $("#stepTimer");

  const render = (dir) => {
    const s = RECIPE[cur];
    $("#stepNo").textContent = cur + 1;
    $("#stepEmoji").textContent = s.e;
    $("#stepText").textContent = s.t;
    $("#stepIngs").innerHTML = s.ings.map((i) => `<span>${i}</span>`).join("");
    if (s.timer) {
      hint.hidden = false;
      hint.textContent = `Select starts a ${fmt(s.timer[1])} ${s.timer[0]} timer`;
    } else hint.hidden = true;
    $$("i", elBars).forEach((b, i) => {
      b.classList.toggle("done", i < cur);
      b.classList.toggle("now", i === cur);
    });
    prevBtn.disabled = cur === 0;
    nextBtn.disabled = cur === RECIPE.length - 1;
    if (dir && !reduced) {
      elInner.classList.remove("swap-in", "swap-back");
      void elInner.offsetWidth;
      elInner.classList.add(dir > 0 ? "swap-in" : "swap-back");
    }
  };
  const go = (d) => {
    const n = Math.max(0, Math.min(RECIPE.length - 1, cur + d));
    if (n !== cur) { cur = n; render(d); }
  };
  prevBtn.addEventListener("click", () => go(-1));
  nextBtn.addEventListener("click", () => go(1));
  const cook = $("#cook");
  cook.addEventListener("keydown", (e) => {
    if (e.key === "ArrowRight") { go(1); e.preventDefault(); }
    if (e.key === "ArrowLeft") { go(-1); e.preventDefault(); }
    if (e.key === "Enter" && !hint.hidden && e.target === cook) hint.click();
  });
  // swipe on touch
  let sx = null;
  cook.addEventListener("touchstart", (e) => (sx = e.touches[0].clientX), { passive: true });
  cook.addEventListener("touchend", (e) => {
    if (sx === null) return;
    const dx = e.changedTouches[0].clientX - sx;
    if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1);
    sx = null;
  });

  // timers: wall-clock end times, like the app; max four
  const timers = new Map();
  const timerBox = $("#cookTimers");
  let audioCtx;
  const chime = () => {
    try {
      audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
      [880, 1175, 1568].forEach((f, i) => {
        const o = audioCtx.createOscillator(), g = audioCtx.createGain();
        o.type = "sine"; o.frequency.value = f;
        const t = audioCtx.currentTime + i * 0.18;
        g.gain.setValueAtTime(0.0001, t);
        g.gain.exponentialRampToValueAtTime(0.18, t + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, t + 0.6);
        o.connect(g).connect(audioCtx.destination);
        o.start(t); o.stop(t + 0.65);
      });
    } catch (_) { /* audio unavailable */ }
  };
  const drawTimers = () => {
    const now = Date.now();
    timers.forEach((t, name) => {
      const left = Math.max(0, Math.round((t.end - now) / 1000));
      t.el.querySelector("b").textContent = fmt(left);
      if (left === 0 && !t.done) {
        t.done = true;
        t.el.classList.add("ding");
        chime();
        setTimeout(() => { t.el.remove(); timers.delete(name); }, 4000);
      }
    });
  };
  setInterval(drawTimers, 250);
  hint.addEventListener("click", () => {
    const [name, secs] = RECIPE[cur].timer;
    // the demo runs timers 20x faster so you can hear the chime
    const demoSecs = Math.round(secs / 20);
    if (timers.has(name)) timers.get(name).el.remove();
    if (timers.size >= 4 && !timers.has(name)) return;
    const el = document.createElement("span");
    el.className = "pill timer";
    el.innerHTML = `<svg viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"/><path d="M12 9v4l2 2M9 2h6"/></svg> ${name} <b>${fmt(demoSecs)}</b>`;
    el.title = "Demo timers run 20× faster";
    timerBox.prepend(el);
    timers.set(name, { end: Date.now() + demoSecs * 1000, el, done: false });
  });
  render(0);

  // ---------- voice conversation loop ----------
  const LINES = [
    ["Alexa, tell Cook Along next step.", "Step 3 of 7. Warm the olive oil, garlic and chili flakes over low heat."],
    ["Alexa, ask Cook Along how much paneer I need.", "375 grams paneer, scaled for six people."],
    ["Alexa, ask Cook Along what can I use instead of cream.", "Try cashew paste or full-fat yogurt, stirred in off the heat."],
    ["Alexa, tell Cook Along to set a timer for five minutes.", "Five-minute timer started. It's on the TV."],
    ["Alexa, ask Cook Along what's for dinner.", "You can make Matar Paneer tonight. You have everything."],
  ];
  const ask = $("#voiceAsk"), reply = $("#voiceReply"), echo = $(".echo");
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const type = async (el, text, speed) => {
    el.classList.add("caret");
    el.textContent = "";
    for (const ch of text) { el.textContent += ch; await wait(speed); }
    el.classList.remove("caret");
  };
  let voiceRunning = false;
  const runVoice = async () => {
    if (voiceRunning) return;
    voiceRunning = true;
    for (let i = 0; ; i = (i + 1) % LINES.length) {
      const [q, a] = LINES[i];
      reply.textContent = " ";
      echo.classList.add("listening");
      if (reduced) ask.textContent = q; else await type(ask, q, 38);
      await wait(500);
      echo.classList.remove("listening");
      if (reduced) reply.textContent = a; else await type(reply, a, 24);
      await wait(2800);
    }
  };
  new IntersectionObserver((e, o) => {
    if (e[0].isIntersecting) { runVoice(); o.disconnect(); }
  }, { threshold: 0.3 }).observe($(".voice-stage"));

  // ---------- copy install command ----------
  $("#copyBtn").addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    try {
      await navigator.clipboard.writeText($("#cmd").textContent);
      btn.textContent = "Copied ✓";
    } catch (_) { btn.textContent = "Select it"; }
    setTimeout(() => (btn.textContent = "Copy"), 1800);
  });
})();


// ---- film: big play button, then native controls ----
(() => {
  const frame = document.getElementById("filmFrame"), video = document.getElementById("filmVideo");
  const play = document.getElementById("filmPlay");
  if (!frame || !video || !play) return;
  play.addEventListener("click", () => { frame.classList.add("playing"); video.play(); });
  video.addEventListener("play", () => frame.classList.add("playing"));
  video.addEventListener("ended", () => frame.classList.remove("playing"));
})();

// ---- read aloud: online plays the real Polly audio; offline uses the browser's own voice, like the TV does ----
(() => {
  const card = document.getElementById("speakCard");
  if (!card) return;
  const engine = document.getElementById("speakEngine"), note = document.getElementById("speakNote");
  const label = document.getElementById("speakLabel"), btn = document.getElementById("speakPlay");
  const line = "Step 2. Add the spaghetti and stir. Cook until just al dente, about 9 minutes.";
  const audio = new Audio("assets/voice/polly-step.mp3");
  const stop = () => {
    audio.pause(); audio.currentTime = 0;
    if ("speechSynthesis" in window) speechSynthesis.cancel();
    card.classList.remove("speaking"); label.textContent = "Hear the step";
  };
  audio.addEventListener("ended", stop);
  const setMode = (mode) => {
    stop();
    card.dataset.mode = mode;
    card.querySelectorAll(".speak-switch button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.mode === mode)));
    engine.textContent = mode === "online" ? "Amazon Polly · generative voice" : "Built-in text-to-speech";
    note.textContent = mode === "online"
      ? "The real Polly audio the TV plays."
      : "Offline, the TV uses its own speech engine. Here, your browser's voice stands in for it.";
  };
  card.querySelectorAll(".speak-switch button").forEach((b) => b.addEventListener("click", () => setMode(b.dataset.mode)));
  btn.addEventListener("click", () => {
    if (card.classList.contains("speaking")) return stop();
    card.classList.add("speaking"); label.textContent = "Playing…";
    if (card.dataset.mode === "online") {
      audio.play().catch(stop);
    } else if ("speechSynthesis" in window) {
      const u = new SpeechSynthesisUtterance(line);
      u.lang = "en-US"; u.rate = 0.95; u.onend = stop; u.onerror = stop;
      speechSynthesis.speak(u);
    } else {
      stop(); note.textContent = "This browser has no built-in voice to demonstrate with.";
    }
  });
})();
