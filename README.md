# 🤖 AiPhamest – 100 % offline. Life‑saving by design

**AiPhamest** is an on‑device Android assistant that keeps you safe, informed, and on‑track with your medication—*without ever sending your data to the cloud.*

Powered by Google’s lightweight **Gemma 3n** multimodal model, AiPhamest reads paper prescriptions, schedules intelligent reminders, analyses side‑effects, and serves bite‑size guidance—all **completely offline**.

---

## ✨ Why it matters

- **40–50 %** of patients take their medications incorrectly; lower adherence increases risk.  
- Drug side‑effects are massively under‑reported, and allergic reactions are often caught too late.  
- Rural & low‑connectivity regions can’t rely on cloud AI.

AiPhamest fixes this with an entirely **edge‑first** stack—bringing AI pharmacy expertise into every pocket, **no internet required**.

---

## 🏆 Key features

| Category | What we built |
|----------|---------------|
| **Multimodal prescription OCR** | CameraX + Gemma 3n vision → extracts *medicine \| strength \| dose \| frequency* with deterministic prompts. |
| **Drug‑name normalisation** | Fuzzy shortlist → Gemma text session chooses canonical generic name; zero hallucinations. |
| **Smart schedules & reminders** | Jetpack Compose UI, adaptive progress bar, snooze, global *pin* for lifelong meds, auto‑classifies doses as *Taken*, *Upcoming*, or *Missed*. |
| **Voice / text side‑effect logger** | Wear‑friendly mic input; offline speech‑to‑text optional. |
| **On‑device causal analysis** | Background WorkManager job rates severity & confidence, then suggests next steps. |
| **Real‑time warnings & tips** | `RecommendationsWorker` streams concise do’s & don’ts for each medicine, with 🔴 / 🟡 / 🟢 severity tags. |
| **Emergency fallback** | Critical events can auto‑send SMS or place a call to a pre‑saved contact. |
| **Privacy by design** | No account, no backend, no analytics. All data lives in an AES‑encrypted Room DB. |

---

## 🛠 Tech stack

- **Android 14** – Kotlin, Jetpack Compose, CameraX, WorkManager  
- **Gemma 3n E4B‑it‑int4** via **MediaPipe Tasks GenAI**  
- **Room DB** for prescriptions, schedules & side‑effects  
- **LlmInference** graphs: vision‑enabled OCR & deterministic text‑only NLP  
- **Coroutines & Flow** for reactive UI  
- **OkHttp** streaming download with checksum‑verified resumable `.part` file  

---




## 🚀 Getting started

```bash
# 1. Clone
git clone https://github.com/your‑org/aiphamest.git
cd aiphamest

# 2. Insert your Hugging Face token
#    app/llm/ModelStore.kt
#    internal const val HF_TOKEN = "<hf_your_token_here>"

# 3. Build & run with Android Studio Hedgehog (AI‑23) or newer.
#    Works on any device API‑26+ with ≥ 6 GB RAM.
```

> ℹ️ **First launch** downloads a ~4 GB quantised Gemma model directly into *app‑private storage*. A built‑in progress UI handles flaky connections & resumes interrupted downloads.

---

1. **PrescriptionExtractor** adds bitmap + system prompt → Gemma returns structured text ending in `###END###`.  
2. **DrugNormalizer** fuzzy‑shortlists candidates, then deterministically picks the exact generic.  
3. **ScheduleEngine** builds daily dose cards & local alarms until the pack is finished (or pinned for life‑long meds).  
4. **SideEffectChecker** (WorkManager) analyses user‑reported symptoms against medication history.  
5. **RecommendationChecker** enriches each med with contextual advice (e.g. “take after food”).  
6. **WarningScreen** visualises warnings and recommendations with severity chips.

---

## 🔒 Privacy & compliance

- Zero network calls once the model is cached *(verified via `adb tcpdump`)*.  
- Local AES‑encrypted SQLCipher DB.  
- Model & prompts comply with **Gemma 3n RAL & IP** guidelines.

---

## 🙌 Acknowledgements

- Google Research & DeepMind for **Gemma 3n**.  
- MediaPipe Tasks GenAI team for the early vision+text API.  
- UnsLoth for open‑source quantisation notebooks.

---

> **Built during the Google Gemma 3n Impact Challenge 2025.**  
> *Empowering safe medication management—everywhere, for everyone.*

---

## 📝 License

[MIT](LICENSE)
