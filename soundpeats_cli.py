#!/usr/bin/env python3
import socket
import sys
import time
import struct
import argparse

# ── PEQ Band Definitions ──────────────────────────────────────────────────
# WuQi WQ7033 PEQ engine: 7 bands identified by hex IDs 0x71–0x77.

PEQ_BANDS = [
    (0x71, 60),      # Sub-bass / Deep Rumble
    (0x72, 180),     # Mid-bass / Punch
    (0x73, 540),     # Lower Mids / Warmth
    (0x74, 1200),    # Mids / Vocals
    (0x75, 3000),    # Upper Mids / Attack
    (0x76, 6600),    # Treble / Sibilance
    (0x77, 15000),   # High Treble / Air
]

Q_FACTOR = 0xFCF4       # Hardcoded Q-factor / bandwidth for all user sliders
PEQ_FOOTER = 0x1000     # PEQ instruction footer
INTER_BAND_DELAY = 0.1  # 100ms between band packets

def gain_db_to_hex(gain_db):
    """Convert dB gain to 16-bit signed hex (two's complement).
    
    Examples:
      +10  dB → 1000  → 0x03E8
      +6.5 dB →  650  → 0x028A
       0   dB →    0  → 0x0000
      -6   dB → -600  → 0xFDA8
      -12  dB → -1200 → 0xFB50
    """
    raw = int(gain_db * 100)
    return raw & 0xFFFF

def build_peq_packet(band_id, freq_hz, gain_db):
    """Build a single 17-byte PEQ instruction packet.
    
    Packet structure:
      ff 04 00 09 00  – Header & payload length (9 bytes follow)
      1d 0e 01        – PEQ engine route
      <band_id>       – Target band (0x71–0x77)
      <q_factor 2B>   – Q-factor (hardcoded 0xFCF4)
      <freq 2B>       – Center frequency in Hz (big-endian)
      <gain 2B>       – Gain = dB × 100, 16-bit signed (two's complement)
      <footer 2B>     – PEQ footer (0x1000)
    """
    gain_hex = gain_db_to_hex(gain_db)
    
    return bytes([
        0xFF, 0x04, 0x00, 0x09, 0x00,    # Header & length
        0x1D, 0x0E, 0x01,                 # PEQ engine route
        band_id,                           # Target band
        (Q_FACTOR >> 8) & 0xFF,           # Q-factor high byte
        Q_FACTOR & 0xFF,                  # Q-factor low byte
        (freq_hz >> 8) & 0xFF,            # Frequency high byte
        freq_hz & 0xFF,                   # Frequency low byte
        (gain_hex >> 8) & 0xFF,           # Gain high byte
        gain_hex & 0xFF,                  # Gain low byte
        (PEQ_FOOTER >> 8) & 0xFF,         # Footer high byte
        PEQ_FOOTER & 0xFF,                # Footer low byte
    ])

def send_payload(mac_address, payload):
    try:
        port = 1  # SPP port
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.connect((mac_address, port))
        
        print(f"Connected to {mac_address}. Sending payload...")
        s.send(payload)
        s.close()
        print("Success.")
    except Exception as e:
        print(f"Attempt failed: {e}")
        print("Please ensure your Soundpeats are connected/paired to your PC and not actively connected to the mobile app.")

def send_peq_bands(mac_address, gains):
    """Send all 7 PEQ band packets sequentially with inter-band delay."""
    try:
        port = 1
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.connect((mac_address, port))
        print(f"Connected to {mac_address}.")
        
        for i, (band_id, freq_hz) in enumerate(PEQ_BANDS):
            gain_db = gains[i]
            packet = build_peq_packet(band_id, freq_hz, gain_db)
            s.send(packet)
            hex_str = ' '.join(f'{b:02x}' for b in packet)
            print(f"  Band 0x{band_id:02X} ({freq_hz:>5}Hz): {gain_db:+.1f} dB → [{hex_str}]")
            
            if i < len(PEQ_BANDS) - 1:
                time.sleep(INTER_BAND_DELAY)
        
        s.close()
        print("All 7 PEQ bands sent successfully.")
    except Exception as e:
        print(f"Attempt failed: {e}")
        print("Please ensure your Soundpeats are connected/paired to your PC and not actively connected to the mobile app.")

def set_anc(mac, mode):
    # mode: 0 = Normal, 1 = ANC, 2 = Transparent
    if mode not in [0, 1, 2]:
        print("Invalid ANC mode. Use 0, 1, or 2.")
        return
    payload = bytearray([0xff, 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x11, mode])
    send_payload(mac, payload)

def set_game_mode(mac, on):
    mode_byte = 0x01 if on else 0x00
    payload = bytearray([0xff, 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x0f, mode_byte])
    send_payload(mac, payload)

def set_eq(mac, gains):
    """Set all 7 EQ bands using proper PEQ per-band protocol."""
    if len(gains) != 7:
        print("Error: Must provide exactly 7 EQ values.")
        return
    
    # Clamp gains to hardware range
    clamped = [max(-12, min(10, g)) for g in gains]
    send_peq_bands(mac, clamped)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Soundpeats Earbuds Controller (Console)")
    parser.add_argument("mac", help="MAC Address of the Soundpeats earbuds (e.g. 11:22:33:44:55:66)")
    parser.add_argument("--anc", type=int, choices=[0, 1, 2], help="Set ANC Mode (0=Normal, 1=ANC, 2=Transparent)")
    parser.add_argument("--game-mode", type=int, choices=[0, 1], help="Set Game Mode (0=Off, 1=On)")
    parser.add_argument("--eq", type=float, nargs=7, metavar=('B1', 'B2', 'B3', 'B4', 'B5', 'B6', 'B7'), 
                        help="Set Custom EQ (7 gain values in dB, range -12 to +10, for bands 60Hz/180Hz/540Hz/1.2kHz/3kHz/6.6kHz/15kHz)")
    
    args = parser.parse_args()
    
    action_taken = False
    if args.anc is not None:
        set_anc(args.mac, args.anc)
        action_taken = True
        
    if args.game_mode is not None:
        set_game_mode(args.mac, args.game_mode == 1)
        action_taken = True
        
    if args.eq is not None:
        set_eq(args.mac, args.eq)
        action_taken = True
        
    if not action_taken:
        parser.print_help()

