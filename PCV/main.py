import cv2
import mediapipe as mp
from mediapipe.tasks import python as mp_tasks
from mediapipe.tasks.python import vision
import pyautogui
import numpy as np
import time
import os

# ══════════════════════════════════════════════
#  CONFIG — all tunable values at the top
# ══════════════════════════════════════════════
CAMERA_INDEX       = 0
CAMERA_W, CAMERA_H = 640, 480

# Head pose → screen mapping
MAX_YAW_DEG        = 8.0
MAX_PITCH_DEG      = 6.0

# Dead zone in degrees
DEAD_ZONE_DEG      = 2.5

# Kalman filter tuning
KALMAN_PROCESS     = 0.003
KALMAN_MEASURE     = 0.8

# Gesture thresholds (set by calibration, these are fallbacks)
WINK_R_THRESH      = 0.5
WINK_L_THRESH      = 0.5
BROW_UP_THRESH     = 0.4
BROW_DOWN_THRESH   = 0.4

# Dwell / cooldown
DWELL_TIME         = 0.35   # seconds
COOLDOWN_TIME      = 0.8    # seconds

# Distance adaptation
BASE_FACE_SCALE    = 0.30
ADAPT_SENSITIVITY  = True

# Calibration sample counts
CALIB_FRAMES       = 40


# ══════════════════════════════════════════════
#  KALMAN FILTER (1D)
# ══════════════════════════════════════════════
class KalmanFilter1D:
    def __init__(self, process_noise=KALMAN_PROCESS, measure_noise=KALMAN_MEASURE):
        self.x = 0.0
        self.P = 1.0
        self.Q = process_noise
        self.R = measure_noise

    def update(self, measurement):
        self.P += self.Q
        K = self.P / (self.P + self.R)
        self.x += K * (measurement - self.x)
        self.P *= (1.0 - K)
        return self.x

    def set(self, value):
        self.x = value


# ══════════════════════════════════════════════
#  GESTURE STATE MACHINE
# ══════════════════════════════════════════════
class GestureStateMachine:
    def __init__(self, name, dwell=DWELL_TIME, cooldown=COOLDOWN_TIME):
        self.name       = name
        self.dwell      = dwell
        self.cooldown   = cooldown
        self.state      = 'IDLE'
        self.start_time = 0
        self.fired      = False

    def update(self, triggered: bool) -> bool:
        now = time.time()
        self.fired = False

        if self.state == 'IDLE':
            if triggered:
                self.state      = 'DETECTING'
                self.start_time = now

        elif self.state == 'DETECTING':
            if not triggered:
                self.state = 'IDLE'
            elif now - self.start_time >= self.dwell:
                self.state      = 'COOLDOWN'
                self.start_time = now
                self.fired      = True

        elif self.state == 'COOLDOWN':
            if now - self.start_time >= self.cooldown:
                self.state = 'IDLE'

        return self.fired

    def get_progress(self) -> float:
        if self.state == 'DETECTING':
            return min((time.time() - self.start_time) / self.dwell, 1.0)
        return 0.0


# ══════════════════════════════════════════════
#  HEAD POSE & UTILITIES
# ══════════════════════════════════════════════
def extract_angles_from_matrix(transformation_matrix):
    m = np.array(transformation_matrix.data).reshape(4, 4)
    R = m[:3, :3]
    pitch = np.degrees(np.arcsin(np.clip(-R[1][2], -1.0, 1.0)))
    yaw   = np.degrees(np.arctan2(R[0][2], R[2][2]))
    roll  = np.degrees(np.arctan2(R[1][0], R[1][1]))
    return float(yaw), float(pitch), float(roll)


def get_face_scale(landmarks):
    left  = landmarks[33]
    right = landmarks[263]
    return abs(right.x - left.x)


def apply_dead_zone(value, dead_zone):
    if abs(value) < dead_zone:
        return 0.0
    return (abs(value) - dead_zone) * np.sign(value)


# ══════════════════════════════════════════════
#  CALIBRATION WIZARD — 5 steps
# ══════════════════════════════════════════════
def run_calibration(cap, detector):
    """
    5-step calibration:
    1. REST       — neutral face, captures baselines
    2. BROW_UP    — raise both eyebrows high
    3. BROW_DOWN  — squint/furrow eyebrows down
    4. WINK_R     — wink right eye (keep left open)
    5. WINK_L     — wink left eye (keep right open)
    """
    SCREEN_W, SCREEN_H = pyautogui.size()

    steps = [
        {"name": "REST",      "instruction": "Neutral face - look straight ahead",
         "sub": "Eyes open, mouth closed, eyebrows relaxed", "frames": 50},
        {"name": "BROW_UP",   "instruction": "RAISE BOTH EYEBROWS high and hold",
         "sub": "Raise as high as comfortable", "frames": 40},
        {"name": "BROW_DOWN", "instruction": "SQUINT/FURROW EYEBROWS down and hold",
         "sub": "Pull eyebrows down as if angry", "frames": 40},
        {"name": "WINK_R",    "instruction": "WINK RIGHT EYE (keep left eye open)",
         "sub": "Hold the wink steady", "frames": 40},
        {"name": "WINK_L",    "instruction": "WINK LEFT EYE (keep right eye open)",
         "sub": "Hold the wink steady", "frames": 40},
    ]

    data = {s["name"]: [] for s in steps}
    ref_yaw, ref_pitch = 0.0, 0.0

    print("\n" + "=" * 50)
    print("  GESTURE CALIBRATION WIZARD")
    print("=" * 50)

    for step_idx, step in enumerate(steps):
        total_steps = len(steps)

        # 3-second countdown
        for countdown in range(3, 0, -1):
            ret, frame = cap.read()
            if not ret:
                continue
            frame = cv2.flip(frame, 1)
            h, w = frame.shape[:2]
            overlay = frame.copy()
            cv2.rectangle(overlay, (0, 0), (w, h), (0, 0, 0), -1)
            cv2.addWeighted(overlay, 0.5, frame, 0.5, 0, frame)
            cv2.putText(frame, f"Step {step_idx+1}/{total_steps}",
                        (w//2-60, h//2-60), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2)
            cv2.putText(frame, step["instruction"],
                        (w//2 - len(step["instruction"])*9, h//2-20),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.65, (255, 255, 255), 2)
            cv2.putText(frame, step["sub"],
                        (w//2 - len(step["sub"])*7, h//2+15),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (180, 180, 180), 1)
            cv2.putText(frame, f"Starting in {countdown}...",
                        (w//2-90, h//2+60), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 0), 2)
            cv2.imshow("PCV - Calibration", frame)
            cv2.waitKey(1000)

        print(f"  [{step_idx+1}/{total_steps}] {step['instruction']}")

        # Minimum signals to accept a frame as "performing the gesture"
        MIN_SIGNAL = {
            "REST":      None,
            "BROW_UP":   ("browInnerUp",     0.08),
            "BROW_DOWN": ("browDownLeft",    0.08),
            "WINK_R":    ("eyeBlinkRight",   0.20),
            "WINK_L":    ("eyeBlinkLeft",    0.20),
        }

        collected = 0
        while collected < step["frames"]:
            ret, frame = cap.read()
            if not ret:
                continue
            frame = cv2.flip(frame, 1)
            h, w = frame.shape[:2]
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            result = detector.detect(mp_img)

            gesture_detected = False
            warning_msg = ""
            live_val = 0.0
            bs = {}

            if result.face_landmarks and result.face_blendshapes:
                bs = {b.category_name: b.score for b in result.face_blendshapes[0]}
                min_check = MIN_SIGNAL[step["name"]]

                if min_check is None:
                    gesture_detected = True
                else:
                    key, min_val = min_check
                    live_val = bs.get(key, 0)

                    if step["name"] == "WINK_R":
                        blink_r = bs.get("eyeBlinkRight", 0)
                        blink_l = bs.get("eyeBlinkLeft", 0)
                        live_val = blink_r
                        if blink_r > 0.20 and blink_l < 0.25:
                            gesture_detected = True
                        elif blink_l > 0.20:
                            warning_msg = "Wrong eye! Wink your RIGHT eye, keep LEFT open"
                        else:
                            warning_msg = f"No wink detected  (right eye: {blink_r:.2f})"

                    elif step["name"] == "WINK_L":
                        blink_r = bs.get("eyeBlinkRight", 0)
                        blink_l = bs.get("eyeBlinkLeft", 0)
                        live_val = blink_l
                        if blink_l > 0.20 and blink_r < 0.25:
                            gesture_detected = True
                        elif blink_r > 0.20:
                            warning_msg = "Wrong eye! Wink your LEFT eye, keep RIGHT open"
                        else:
                            warning_msg = f"No wink detected  (left eye: {blink_l:.2f})"

                    elif step["name"] == "BROW_DOWN":
                        brow_down = (bs.get("browDownLeft", 0) + bs.get("browDownRight", 0)) / 2
                        live_val = brow_down
                        if brow_down >= min_val:
                            gesture_detected = True
                        else:
                            warning_msg = f"Squint/furrow harder!  (detected: {brow_down:.2f})"

                    else:
                        if live_val >= min_val:
                            gesture_detected = True
                        else:
                            if step["name"] == "BROW_UP":
                                warning_msg = f"Raise eyebrows higher!  (detected: {live_val:.2f})"

                if gesture_detected:
                    sample = {
                        "browInnerUp":   bs.get("browInnerUp", 0),
                        "browOuterUpL":  bs.get("browOuterUpLeft", 0),
                        "browOuterUpR":  bs.get("browOuterUpRight", 0),
                        "browDownL":     bs.get("browDownLeft", 0),
                        "browDownR":     bs.get("browDownRight", 0),
                        "eyeBlinkR":     bs.get("eyeBlinkRight", 0),
                        "eyeBlinkL":     bs.get("eyeBlinkLeft", 0),
                    }
                    data[step["name"]].append(sample)
                    collected += 1

                    # Capture head angles during REST
                    if step["name"] == "REST" and result.facial_transformation_matrixes:
                        y, p, _ = extract_angles_from_matrix(
                            result.facial_transformation_matrixes[0])
                        ref_yaw   += y / step["frames"]
                        ref_pitch += p / step["frames"]

            # ── Draw calibration UI ──────────────────────
            progress = collected / step["frames"]
            bar_w_px = int(progress * (w - 80))
            cv2.rectangle(frame, (40, h-50), (w-40, h-30), (80, 80, 80), -1)
            cv2.rectangle(frame, (40, h-50), (40+bar_w_px, h-30), (0, 255, 0), -1)
            cv2.rectangle(frame, (40, h-50), (w-40, h-30), (255, 255, 255), 2)

            cv2.putText(frame, f"Step {step_idx+1}/{total_steps}: {step['instruction']}",
                        (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (0, 255, 255), 2)
            cv2.putText(frame, step["sub"],
                        (20, 68), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (180, 180, 180), 1)

            cv2.putText(frame, f"Valid samples: {collected}/{step['frames']}",
                        (20, h-58), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)

            if warning_msg:
                cv2.rectangle(frame, (0, h//2-35), (w, h//2+10), (0, 0, 150), -1)
                cv2.putText(frame, warning_msg,
                            (20, h//2), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 100, 255), 2)
            elif gesture_detected and step["name"] != "REST":
                cv2.putText(frame, "Gesture detected! Hold it...",
                            (20, h//2), cv2.FONT_HERSHEY_SIMPLEX, 0.65, (0, 255, 0), 2)

            # Live value display
            if step["name"] == "BROW_UP":
                cv2.putText(frame, f"browInnerUp: {live_val:.3f}  (need > 0.08)",
                            (20, h//2+35), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
            elif step["name"] == "BROW_DOWN":
                cv2.putText(frame, f"browDown: {live_val:.3f}  (need > 0.08)",
                            (20, h//2+35), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)
            elif step["name"] in ("WINK_R", "WINK_L"):
                r_live = bs.get("eyeBlinkRight", 0)
                l_live = bs.get("eyeBlinkLeft", 0)
                cv2.putText(frame, f"RIGHT: {r_live:.3f}  LEFT: {l_live:.3f}",
                            (20, h//2+35), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (200, 200, 200), 1)

            cv2.imshow("PCV - Calibration", frame)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                return None

    # ── Calculate thresholds ──────────────────────
    def mean_key(step_name, key):
        return np.mean([s[key] for s in data[step_name]]) if data[step_name] else 0

    # Brow UP
    rest_brow_up = (mean_key("REST", "browInnerUp") +
                    mean_key("REST", "browOuterUpL") +
                    mean_key("REST", "browOuterUpR")) / 3
    peak_brow_up = (mean_key("BROW_UP", "browInnerUp") +
                    mean_key("BROW_UP", "browOuterUpL") +
                    mean_key("BROW_UP", "browOuterUpR")) / 3
    brow_up_thresh = rest_brow_up + 0.65 * (peak_brow_up - rest_brow_up)
    brow_up_thresh = max(0.15, min(0.9, brow_up_thresh))

    # Brow DOWN
    rest_brow_down = (mean_key("REST", "browDownL") + mean_key("REST", "browDownR")) / 2
    peak_brow_down = (mean_key("BROW_DOWN", "browDownL") + mean_key("BROW_DOWN", "browDownR")) / 2
    brow_down_thresh = rest_brow_down + 0.65 * (peak_brow_down - rest_brow_down)
    brow_down_thresh = max(0.10, min(0.9, brow_down_thresh))

    # Wink R
    rest_winkR = mean_key("REST", "eyeBlinkR")
    peak_winkR = mean_key("WINK_R", "eyeBlinkR")
    wink_r_thresh = rest_winkR + 0.65 * (peak_winkR - rest_winkR)
    wink_r_thresh = max(0.20, min(0.9, wink_r_thresh))

    # Wink L
    rest_winkL = mean_key("REST", "eyeBlinkL")
    peak_winkL = mean_key("WINK_L", "eyeBlinkL")
    wink_l_thresh = rest_winkL + 0.65 * (peak_winkL - rest_winkL)
    wink_l_thresh = max(0.20, min(0.9, wink_l_thresh))

    print(f"\n  Calibrated thresholds:")
    print(f"    Brow UP:   {brow_up_thresh:.3f}  (rest {rest_brow_up:.3f} -> peak {peak_brow_up:.3f})")
    print(f"    Brow DOWN: {brow_down_thresh:.3f}  (rest {rest_brow_down:.3f} -> peak {peak_brow_down:.3f})")
    print(f"    WinkR:     {wink_r_thresh:.3f}  (rest {rest_winkR:.3f} -> peak {peak_winkR:.3f})")
    print(f"    WinkL:     {wink_l_thresh:.3f}  (rest {rest_winkL:.3f} -> peak {peak_winkL:.3f})")
    print(f"    Neutral head pose: yaw={ref_yaw:.2f} deg  pitch={ref_pitch:.2f} deg")

    # Show results for 3 seconds
    for _ in range(90):
        ret, frame = cap.read()
        if not ret:
            break
        frame = cv2.flip(frame, 1)
        h, w = frame.shape[:2]
        cv2.putText(frame, "Calibration Complete!", (w//2-160, h//2-30),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 0), 2)
        cv2.putText(frame, f"BrowUp:{brow_up_thresh:.2f} BrowDn:{brow_down_thresh:.2f} WinkR:{wink_r_thresh:.2f} WinkL:{wink_l_thresh:.2f}",
                    (20, h//2+20), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
        cv2.imshow("PCV - Calibration", frame)
        cv2.waitKey(33)

    cv2.destroyWindow("PCV - Calibration")
    return {
        "brow_up": brow_up_thresh, "brow_down": brow_down_thresh,
        "wink_r": wink_r_thresh, "wink_l": wink_l_thresh,
        "ref_yaw": ref_yaw, "ref_pitch": ref_pitch
    }


# ══════════════════════════════════════════════
#  MAIN LOOP
# ══════════════════════════════════════════════
def main():
    global WINK_R_THRESH, WINK_L_THRESH, BROW_UP_THRESH, BROW_DOWN_THRESH

    SCREEN_W, SCREEN_H = pyautogui.size()
    pyautogui.FAILSAFE = False

    cap = cv2.VideoCapture(CAMERA_INDEX)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH,  CAMERA_W)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAMERA_H)

    # Load model
    model_path = "face_landmarker.task"
    if not os.path.exists(model_path):
        print(f"ERROR: '{model_path}' not found!")
        print("Download: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task")
        return

    options = vision.FaceLandmarkerOptions(
        base_options=mp_tasks.BaseOptions(model_asset_path=model_path),
        running_mode=vision.RunningMode.IMAGE,
        num_faces=1,
        min_face_detection_confidence=0.6,
        min_face_presence_confidence=0.6,
        min_tracking_confidence=0.6,
        output_face_blendshapes=True,
        output_facial_transformation_matrixes=True
    )
    detector = vision.FaceLandmarker.create_from_options(options)

    # Run calibration
    print("=" * 50)
    print("  PCV — Face Control  |  Q = quit  R = recalibrate")
    print("=" * 50)

    cal = run_calibration(cap, detector)
    if cal is None:
        print("  Calibration cancelled. Using defaults.")
        ref_yaw, ref_pitch = 0.0, 0.0
    else:
        BROW_UP_THRESH   = cal["brow_up"]
        BROW_DOWN_THRESH = cal["brow_down"]
        WINK_R_THRESH    = cal["wink_r"]
        WINK_L_THRESH    = cal["wink_l"]
        ref_yaw          = cal["ref_yaw"]
        ref_pitch        = cal["ref_pitch"]

    # Initialize Kalman filters
    kf_x = KalmanFilter1D()
    kf_y = KalmanFilter1D()
    kf_x.set(SCREEN_W / 2)
    kf_y.set(SCREEN_H / 2)

    # Gesture state machines
    # Winks → clicks
    fsm_lclick  = GestureStateMachine("LEFT_CLICK",   dwell=DWELL_TIME)
    fsm_rclick  = GestureStateMachine("RIGHT_CLICK",  dwell=DWELL_TIME)
    # Brow → scroll
    fsm_scroll_up   = GestureStateMachine("SCROLL_UP",   dwell=DWELL_TIME)
    fsm_scroll_down = GestureStateMachine("SCROLL_DOWN", dwell=DWELL_TIME)

    face_scale_history = []
    fps_timer = time.time()
    fps = 0
    frame_count = 0

    print("\n  PCV running — Q to quit, R to recalibrate")
    print("  Controls:")
    print("    Left wink  → Left click")
    print("    Right wink → Right click")
    print("    Raise brows   → Scroll UP")
    print("    Squint brows  → Scroll DOWN\n")

    while cap.isOpened():
        ret, frame = cap.read()
        if not ret:
            break

        frame = cv2.flip(frame, 1)
        h, w = frame.shape[:2]
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

        mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        result = detector.detect(mp_img)

        frame_count += 1
        if time.time() - fps_timer >= 1.0:
            fps = frame_count
            frame_count = 0
            fps_timer = time.time()

        if (result.face_landmarks and
            result.facial_transformation_matrixes and
            result.face_blendshapes):

            landmarks   = result.face_landmarks[0]
            blendshapes = {b.category_name: b.score
                           for b in result.face_blendshapes[0]}

            # ── Draw face mesh ──
            for lm in landmarks:
                cv2.circle(frame, (int(lm.x * w), int(lm.y * h)), 1, (0, 200, 0), -1)

            # ── Head angles ──
            yaw, pitch, roll = extract_angles_from_matrix(
                result.facial_transformation_matrixes[0])

            delta_yaw   = yaw   - ref_yaw
            delta_pitch = pitch - ref_pitch

            delta_yaw   = apply_dead_zone(delta_yaw,   DEAD_ZONE_DEG)
            delta_pitch = apply_dead_zone(delta_pitch,  DEAD_ZONE_DEG)

            # ── Distance-adaptive sensitivity ──
            face_scale = get_face_scale(landmarks)
            face_scale_history.append(face_scale)
            if len(face_scale_history) > 30:
                face_scale_history.pop(0)
            smooth_scale = np.mean(face_scale_history)

            if ADAPT_SENSITIVITY and smooth_scale > 0.01:
                dist_multiplier = BASE_FACE_SCALE / smooth_scale
                dist_multiplier = np.clip(dist_multiplier, 0.4, 3.0)
            else:
                dist_multiplier = 1.0

            # ── Direct mapping: angle → screen position ──
            norm_x = delta_yaw   / (MAX_YAW_DEG   * dist_multiplier)
            norm_y = delta_pitch / (MAX_PITCH_DEG  * dist_multiplier)
            norm_x = np.clip(norm_x, -1.0, 1.0)
            norm_y = np.clip(norm_y, -1.0, 1.0)

            target_x = SCREEN_W / 2 + norm_x * SCREEN_W / 2
            target_y = SCREEN_H / 2 + norm_y * SCREEN_H / 2

            smooth_x = int(np.clip(kf_x.update(target_x), 0, SCREEN_W))
            smooth_y = int(np.clip(kf_y.update(target_y), 0, SCREEN_H))

            pyautogui.moveTo(smooth_x, smooth_y, _pause=False)

            # ── Gesture detection ──
            blink_r  = blendshapes.get("eyeBlinkRight", 0)
            blink_l  = blendshapes.get("eyeBlinkLeft", 0)

            # Brow raise (scroll up) — use browInnerUp only (most reliable)
            brow_up = blendshapes.get("browInnerUp", 0)

            # Brow squint/furrow (scroll down) — average of browDown blendshapes
            brow_down = (blendshapes.get("browDownLeft", 0) +
                         blendshapes.get("browDownRight", 0)) / 2

            # Left wink = left eye closed AND right eye OPEN → Left click
            wink_l_active = blink_l > WINK_L_THRESH and blink_r < 0.3
            if fsm_lclick.update(wink_l_active):
                pyautogui.click()
                print("  [CLICK] Left click (left wink)")

            # Right wink = right eye closed AND left eye OPEN → Right click
            wink_r_active = blink_r > WINK_R_THRESH and blink_l < 0.3
            if fsm_rclick.update(wink_r_active):
                pyautogui.rightClick()
                print("  [CLICK] Right click (right wink)")

            # Brow raise → Scroll UP
            if fsm_scroll_up.update(brow_up > BROW_UP_THRESH):
                pyautogui.scroll(3)
                print("  [SCROLL] Up (brow raise)")

            # Brow squint/furrow → Scroll DOWN
            if fsm_scroll_down.update(brow_down > BROW_DOWN_THRESH):
                pyautogui.scroll(-3)
                print("  [SCROLL] Down (brow squint)")

            # ── Draw gesture progress bars ──
            gestures_list = [
                ("WINK_L",   blink_l,   WINK_L_THRESH,    fsm_lclick,      (100, 255, 100)),
                ("WINK_R",   blink_r,   WINK_R_THRESH,    fsm_rclick,      (100, 100, 255)),
                ("BROW_UP",  brow_up,   BROW_UP_THRESH,   fsm_scroll_up,   (255, 200, 0)),
                ("BROW_DN",  brow_down, BROW_DOWN_THRESH, fsm_scroll_down, (200, 100, 255)),
            ]
            for i, (name, val, thresh, fsm, color) in enumerate(gestures_list):
                bar_y = h - 30 - i * 28
                cv2.rectangle(frame, (10, bar_y-12), (200, bar_y+5), (50, 50, 50), -1)
                bar_len = int(min(val / max(thresh, 0.001), 1.5) * 190)
                cv2.rectangle(frame, (10, bar_y-12), (10+bar_len, bar_y+5), color, -1)
                thresh_x = int(thresh / 1.5 * 190) + 10
                cv2.line(frame, (thresh_x, bar_y-14), (thresh_x, bar_y+7), (255, 255, 255), 2)
                prog = fsm.get_progress()
                if prog > 0:
                    cv2.putText(frame, f"{name} {int(prog*100)}%",
                                (10, bar_y+3), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)
                else:
                    cv2.putText(frame, f"{name} {val:.2f}/{thresh:.2f}",
                                (10, bar_y+3), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (200, 200, 200), 1)

            # ── HUD ──
            cv2.putText(frame, f"PCV  FPS:{fps}  dist_mult:{dist_multiplier:.2f}  [Q=quit R=recal]",
                        (10, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
            cv2.putText(frame, f"Yaw:{delta_yaw:+.1f} deg  Pitch:{delta_pitch:+.1f} deg  face_scale:{smooth_scale:.3f}",
                        (10, 44), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (180, 180, 180), 1)

        else:
            cv2.putText(frame, "No face detected",
                        (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
            cv2.putText(frame, "PCV  [Q=quit]", (10, 22),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

        cv2.imshow("PCV - Face Control", frame)
        key = cv2.waitKey(1) & 0xFF
        if key == ord('q'):
            break
        elif key == ord('r'):
            cal = run_calibration(cap, detector)
            if cal:
                BROW_UP_THRESH   = cal["brow_up"]
                BROW_DOWN_THRESH = cal["brow_down"]
                WINK_R_THRESH    = cal["wink_r"]
                WINK_L_THRESH    = cal["wink_l"]
                ref_yaw          = cal["ref_yaw"]
                ref_pitch        = cal["ref_pitch"]
                kf_x.set(SCREEN_W / 2)
                kf_y.set(SCREEN_H / 2)

    detector.close()
    cap.release()
    cv2.destroyAllWindows()
    print("  PCV closed.")


if __name__ == "__main__":
    main()
