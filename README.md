# external-funder

Core issue: opening a lightning channel requires an on-chain transaction, hence a channel funder needs to have enough BTC available in a local wallet. This may not always be the case, especially when an app is installed for the first time. 

In such degenerate cases users will have to send a first transaction from outside (another wallet, exchange) into their local wallet first, and then a second transaction from a local wallet to a channel. An intermediary outside -> wallet transaction is totally useless here, it consumes additional time and user effort, creates an unwanted blockchain load and doubles an on-chain fees a funder has to pay.

---

Solution: payment channel on a user's device can be funded from a remote exchange using a single on-chain transaction. Exchange may do this by utilizing user's trading balance but also by providing something like fiat -> channel service (if you plan to ever touch fiat). Exchange may earn on this by additionally charging user with some extra fee for this type of withdrawal. Other than this, exchange may benefit from hype and attract new users by providing this service.

---

Complexities: channel funding protocol is interactive, meaning an automatic negotiation has to happen between Alice the funder and Bob the remote fundee LN node before Alice publishes a funding transaction. This in turn means a communication between Alice and Exchange has to be happening in parallel.

Here's how it looks from Exchange's point of view:

1. Alice the trader orders a funding amount using her trading balance.
	- At this point Exchange has to allocate an unspent output for a future transaction and reserve it for Alice.
	- Once done, Exchange has to notify Alice about funding availability, this may not happen immediately as Exchange may need some time (perhaps hours) to find fitting unspent outputs.
	- Alice's reservation should have an expiration in case if she never proceeds after ordering a funding, probably some throttling mechanism should also be in place for users who order reservations repeatedly and do not proceed with them (thankfully this is easy to do on a centralized exchange with user accounts).

2. Once notified, Alice logs in to an Exchange site, and uses her phone to scan a special QR which contains all the required info to authenticate Alice.

3. In a middle of Alice <-> Bob negotiation, Alice device sends a message to Exchange which says "please use my reserved outputs to create a transaction which spends my funds to this pubkeyScript, BUT do not publish a transaction yet, just give me a txid and a funding output index"
	- This step may be repeated multiple times as Alice potentially negotiates with multiple Bobs because the first one is unresponsive/inconsistent/whatever
	- Important note: all the inputs in this transaction should be segwit so the resulting txid can not be malleated, Exchange should support segwit for on-chain deposits so it later has segwit outputs to select from.

4. Once Alice <-> Bob negotiation is complete, Alice sends a message to Exchange which says "OK, please now publish my reserved transaction with such txid"
	- This is it for an Exchange, it's job is done here.

---

Software which does an Alice <-> Exchange communication part: https://github.com/btcontract/external-funder, it's supposed to run on an exchange server and is designed to be extensible for a specific needs an exchange has. User authentication, outputs selection, throttling strategies etc. are supposed to be executed internally by an exchange with external-funder getting notified about results and relaying them to a user using a standard protocol.

--- 

Working demo:

1. Install a Bitcoin Lightning Wallet TESTNET: https://play.google.com/store/apps/details?id=com.lightning.wallet&hl=en

2. Go to http://funder.lightning-wallet.com/funder/ and order a 1.000.000 SAT amount, then scan a QR using wallet and get a local channel funded from a Bitcoin Core instance located at lightning-wallet.com
