# 🤖 AiPhamest – 100 % offline. Life‑saving by design

**AiPhamest** AiPhamest is an intelligent, offline medical assistant that uses Gemma 3n to automate prescription understanding, build smart medication schedules, and monitor side effects — all while ensuring patient safety through drug interaction analysis, allergy checks, and emergency alerts.*

Built entirely with on-device multimodal AI, AiPhamest helps patients adhere to their medication plans and catch potentially dangerous side effects, even without internet access. The system blends structured LLM reasoning, man-in-the-loop verification, and real-time alerting for critical drug-related risks. **completely offline**.

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
| **Emergency fallback** | Critical events can auto‑send SMS or place a call to a pre‑saved contact. (Upcoming) |
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

## ⚠️ Known Limitations & Notes

- ⏱️ **Initial extraction may take up to 2 minutes**, as the app runs the Gemma 3n model twice:
  1. First to extract structured prescription data from the image
  2. Then to match the extracted drug name against entries in `drug.txt`

- 🎙️ **Voice input currently uses Google's on-device speech recognition API.**  
  This will be replaced with **Gemma 3n’s audio model for Android** once it becomes available.

- 📂 **To improve extraction accuracy**, please add relevant **drug names to the `drug.txt` file** in the app’s `/assets/` folder **before extracting prescriptions**.  
  This helps the app match medications more precisely.

- 🔧 The model will be fine-tuned in future iterations to include comprehensive datasets for **drug names, side effects, and drug interactions**, improving reasoning and recommendations.

---

## 🧠 LLM Integration Code Path

All code related to the **Gemma 3n on-device model integration** is located in: "app/src/main/java/com/example/AiPhamest/llm/"

This includes:

- "PrescriptionExtractor.kt" – Handles structured OCR from prescription images using Gemma 3n vision model  
- "DrugNormalizer.kt" – Resolves fuzzy medicine names to canonical drug terms using deterministic text inference  
- "RecommendationChecker.kt" – Provides concise contextual advice for each medication (e.g. “take after food”)  
- "SideEffectChecker.kt" – Analyzes user-reported symptoms in relation to medication history  
- "LlmModelStore.kt" – Manages model download, caching, and access (using Hugging Face + MediaPipe Tasks GenAI)  
- "Fuzzy.kt" – Lightweight string matcher used for shortlist generation prior to model disambiguation

> These components form the LLM inference pipeline used throughout the app to support structured extraction, intelligent scheduling, and offline reasoning — all powered by **Gemma 3n**, completely on-device.

---


## 🚀 Getting started

```bash
# 1. Clone
git clone https://github.com/AiPhamest/AiPhamest
cd aiphamest

// 2. Insert your Hugging Face token
// File: app/llm/ModelStore.kt
internal const val HF_TOKEN = "<hf_your_token_here>"

# 3. Build & run with Android Studio Hedgehog (AI‑23) or newer.

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

---

> **Built during the Google Gemma 3n Impact Challenge 2025.**  
> *Empowering safe medication management—everywhere, for everyone.*

---

## 📝 License

[MIT](LICENSE)
