"""Emulator-only helper: lets a phone on your Wi-Fi open the CookAlong shopping-list QR link.

A real Fire TV serves the list on its own Wi-Fi address, so this is NOT needed on hardware. The Android
emulator lives on a private 10.0.2.x network a phone can't reach, so we relay:

    phone --Wi-Fi--> <pc-ip>:8765 (this script) --> 127.0.0.1:8765 (adb forward) --> emulator:8765 (the app)

Usage (from the repo root):
    adb -s emulator-5580 forward tcp:8765 tcp:8765
    python tools/qr_relay.py 192.168.0.62          # your PC's Wi-Fi IPv4 (ipconfig)
    adb -s emulator-5580 shell am start -S -n com.cookalong.app/.MainActivity --es companionHost 192.168.0.62:8765

Windows may ask to allow Python on private networks; allow it for the phone to connect. Ctrl+C stops it.
"""
import socket
import sys
import threading

PORT = 8765


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    listen_ip = sys.argv[1]
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind((listen_ip, PORT))  # the LAN address only, never 0.0.0.0
    server.listen(16)
    print(f"Relaying http://{listen_ip}:{PORT} -> 127.0.0.1:{PORT} (adb forward). Ctrl+C to stop.")
    while True:
        client, addr = server.accept()
        try:
            upstream = socket.create_connection(("127.0.0.1", PORT), timeout=5)
        except OSError:
            client.close()
            print("Could not reach the emulator. Is `adb forward tcp:8765 tcp:8765` set and the QR shown once?")
            continue
        threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
