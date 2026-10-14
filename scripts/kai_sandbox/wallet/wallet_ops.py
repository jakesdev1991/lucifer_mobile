#!/usr/bin/env python3
"""
Kai Sandbox - Wallet Operations
Run in Kai sandbox via: python3 wallet_ops.py <json_args>
"""

import json
import sys
import os
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.backends import default_backend
from Crypto.Hash import keccak
import json as json_lib

def keccak256(data: bytes) -> bytes:
    k = keccak.new(digest_bits=256)
    k.update(data)
    return k.digest()

def to_checksum_address(addr_hex_lower: str) -> str:
    addr = addr_hex_lower.lower().replace('0x','')
    h = keccak256(addr.encode()).hex()
    out = '0x'
    for i, c in enumerate(addr):
        if c in '0123456789':
            out += c
        else:
            out += c.upper() if int(h[i],16) >= 8 else c.lower()
    return out

def generate_wallet(chain="arbitrum"):
    """Generate secp256k1 keypair for Arbitrum/Ethereum"""
    priv = ec.generate_private_key(ec.SECP256K1(), default_backend())
    priv_num = priv.private_numbers().private_value
    priv_bytes = priv_num.to_bytes(32, 'big')
    
    pub = priv.public_key().public_numbers()
    x = pub.x.to_bytes(32, 'big')
    y = pub.y.to_bytes(32, 'big')
    pub_bytes = x + y  # 64-byte uncompressed
    
    # Ethereum address = last 20 bytes of keccak256(uncompressed pub)
    addr = keccak256(pub_bytes)[-20:]
    addr_lower = '0x' + addr.hex()
    checksummed = to_checksum_address(addr_lower)
    
    return {
        "chain": chain,
        "address": checksummed,
        "public_key": '0x' + pub_bytes.hex(),
        "private_key": '0x' + priv_bytes.hex(),
    }

def load_wallet(address):
    # Load from Kai sandbox keystore
    keystore_path = f"/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/keystore/{address.lower()}.json"
    if os.path.exists(keystore_path):
        with open(keystore_path) as f:
            return json.load(f)
    return None

def save_wallet(wallet):
    keystore_dir = "/data/data/com.inspiredandroid.kai/files/linux-sandbox/tmp/keystore"
    os.makedirs(keystore_dir, exist_ok=True)
    path = f"{keystore_dir}/{wallet['address'].lower()}.json"
    with open(path, 'w') as f:
        json.dump(wallet, f, indent=2)
    return True

def sign_transaction(from_addr, to_addr, value_wei, gas_limit, max_fee_per_gas, max_priority_fee_per_gas, data="0x", chain_id="42161"):
    # Load private key
    wallet = load_wallet(from_addr)
    if not wallet:
        return {"error": "Wallet not found"}
    
    priv_hex = wallet['private_key'].replace('0x', '')
    priv_bytes = bytes.fromhex(priv_hex)
    
    # Build EIP-1559 transaction
    # This is a simplified version - in production use proper RLP encoding
    from rlp import encode
    from eth_account import Account
    
    acct = Account.from_key(wallet['private_key'])
    tx = {
        'to': to_addr,
        'value': int(value_wei, 0) if value_wei.startswith('0x') else int(value_wei),
        'gas': int(gas_limit, 0) if gas_limit.startswith('0x') else int(gas_limit),
        'maxFeePerGas': int(max_fee_per_gas, 0) if max_fee_per_gas.startswith('0x') else int(max_fee_per_gas),
        'maxPriorityFeePerGas': int(max_priority_fee_per_gas, 0) if max_priority_fee_per_gas.startswith('0x') else int(max_priority_fee_per_gas),
        'data': data,
        'chainId': int(chain_id),
    }
    
    signed = Account.sign_transaction(tx, wallet['private_key'])
    return {"signed_tx": signed.rawTransaction.hex()}

def get_balance(address, token="ETH"):
    # Would connect to Arbitrum RPC
    return {"balance": "0", "token": token}

def get_nonce(address):
    return {"nonce": 0}

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: python3 wallet_ops.py '<json_args>'"}))
        return
    
    try:
        args = json.loads(sys.argv[1])
    except json.JSONDecodeError:
        print(json.dumps({"error": "Invalid JSON args"}))
        return
    
    action = args.get("action", "generate")
    chain = args.get("chain", "arbitrum")
    
    try:
        if action == "generate":
            result = generate_wallet(chain)
        elif action == "load":
            result = load_wallet(args.get("address", ""))
        elif action == "sign_transaction":
            result = sign_transaction(
                args.get("from"), args.get("to"), args.get("value"),
                args.get("gas_limit"), args.get("max_fee_per_gas"),
                args.get("max_priority_fee_per_gas"), args.get("data", "0x"),
                args.get("chain_id", "42161")
            )
        elif action == "get_balance":
            result = get_balance(args.get("address"), args.get("token", "ETH"))
        elif action == "get_nonce":
            result = get_nonce(args.get("address"))
        else:
            result = {"error": f"Unknown action: {action}"}
    except Exception as e:
        result = {"error": str(e)}
    
    print(json.dumps(result))

if __name__ == "__main__":
    main()