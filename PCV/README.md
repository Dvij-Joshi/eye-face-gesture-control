# PCV — PC Face Control
Control your laptop with just your face. Built for accessibility.

---

## Setup (do this once)

```bash
# 1. Open this folder in terminal / VS Code terminal

# 2. Create virtual environment
python -m venv venv

# 3. Activate it
# Windows:
venv\Scripts\activate
# Mac/Linux:
source venv/bin/activate

# 4. Install dependencies
pip install -r requirements.txt
```

---

## Run

```bash
python main.py
```

---

## Controls

| Gesture            | Action         |
|--------------------|----------------|
| Move head          | Move cursor    |
| Open mouth wide    | Left click     |
| Wink right eye     | Right click    |
| Raise both eyebrows| Scroll up      |

---

## Tuning (inside main.py top section)

| Setting            | What it does                              |
|--------------------|-------------------------------------------|
| `HEAD_SENSITIVITY` | How fast head moves cursor (default 8)    |
| `SMOOTHING`        | Cursor smoothness (higher = smoother)     |
| `MOUTH_THRESHOLD`  | How wide to open for click (default 0.06) |
| `WINK_THRESHOLD`   | Eye closeness for wink (default 0.015)    |
| `BROW_THRESHOLD`   | Brow height to trigger action (0.27)      |
| `CLICK_COOLDOWN`   | Seconds between clicks (default 1.0)      |

The HUD at the bottom of the camera window shows **live values** so you
can see exactly what numbers your gestures produce — adjust thresholds to match.

---

## Calibration
When you first run it, keep your head **still and centered** for ~20 frames.
That position becomes your "neutral" — the cursor center point.

---

## Roadmap
- [ ] Config file (no need to edit code)
- [ ] Gesture profiles (gaming, browsing, typing assist)
- [ ] Scroll with head tilt
- [ ] Android version (Kotlin + MediaPipe)
