
import struct


class PayloadSender:

    def __init__(self, sock=None):
        self.payload = bytes()
        self.socket = sock

    def reset_payload(self):
        self.payload = bytes()

    def get_payload(self):
        return self.payload

    def set_socket(self, a_socket):
        self.socket = a_socket

    def send(self, reset_payload=False):
        if self.socket is None:
            return False
        else:
            self.socket.setblocking(True)
            self.socket.send(self.payload)
            self.socket.setblocking(False)
            if reset_payload:
                self.reset_payload()
            return True
        

    def set_payload(self, value):
        print("set payload with some data ",value)
        self.payload = value

    def set_payload_string(self, value):
        print("set payload with some data ",value)
        self.payload = value.encode('utf-8')
