import time
import socket
import binascii
import payloadSender
import struct

from network import LoRa

class TTNJoiner:
    def __init__(self, lora_payload, dev_addr, nwk_swkey, app_swkey):
        self.lora_payload = lora_payload
        self.dev_addr = struct.unpack(">l", binascii.unhexlify(dev_addr))[0]
        self.nwk_swkey = binascii.unhexlify(nwk_swkey)
        self.app_swkey = binascii.unhexlify(app_swkey)
        self.lora = LoRa(mode=LoRa.LORAWAN, region=LoRa.EU868)
        self.create_socket()
        
    def join(self):
        self.lora.join(activation=LoRa.ABP, auth=(self.dev_addr, self.nwk_swkey, self.app_swkey))
        while not self.lora.has_joined():
            time.sleep(2.5)
            print('Not yet joined...')
        


    def create_socket(self):
        # create a LoRa socket
        s = socket.socket(socket.AF_LORA, socket.SOCK_RAW)
        s.setsockopt(socket.SOL_LORA, socket.SO_DR, 5)
        s.setblocking(False)
        self.socket=s

    def send_data_bytes(self,data):
        # creating Payload Sender packet
        lpp = payloadSender.PayloadSender(sock=self.socket)
        lpp.set_payload(data)
        lpp.send(reset_payload=True)

    def send_data_string(self,data):
        # creating Payload Sender packet
        lpp = payloadSender.PayloadSender(sock=self.socket)
        lpp.set_payload_string(data)
        lpp.send(reset_payload=True)

    def receive_data_blocking(self):
        return receive_data_blocking(self.socket)

def receive_data_blocking(sock):
    byte_size = 64
    # Create a buffer to hold incoming data
    # Receive data from the socket
    data = sock.recv(byte_size)
    # Parse the received data
    decode_data = binascii.hexlify(data).decode()
    print("Received data:", decode_data)
    # Do something with the received data here
    return decode_data
