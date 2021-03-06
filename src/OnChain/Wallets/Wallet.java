package OnChain.Wallets;

import java.lang.Thread.State;
import java.nio.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.*;

import javax.crypto.*;

import org.bouncycastle.math.ec.ECPoint;

import OnChain.*;
import OnChain.Core.*;
import OnChain.Core.Scripts.Script;
import OnChain.Cryptography.*;
import OnChain.IO.Caching.*;

public abstract class Wallet implements AutoCloseable
{
    // TODO
    //public event EventHandler BalanceChanged;

    public static final byte COIN_VERSION = 0x17;

    private final String path;
    private byte[] iv;
    private byte[] masterKey;
    private Map<UInt160, Account> accounts;
    private Map<UInt160, Contract> contracts;
    private TrackableCollection<TransactionInput, Coin> coins;
    private int current_height;

    private Thread thread;
    private boolean isrunning = true;

    protected String dbPath() { return path; }
    protected final Object syncroot = new Object();
    protected int walletHeight() {
        return current_height;
    }

    public void syncBlockChain() throws Exception {
    	int block_height = Blockchain.current().height();
    	while(current_height < block_height) {
    		Thread.sleep(100);
    	}
    }
    private Wallet(String path, byte[] passwordKey, boolean create) throws BadPaddingException, IllegalBlockSizeException
    {
        this.path = path;
        if (create)
        {
            this.iv = AES.generateIV();
            this.masterKey = AES.generateKey();
            this.accounts = new HashMap<UInt160, Account>();
            this.contracts = new HashMap<UInt160, Contract>();
            this.coins = new TrackableCollection<TransactionInput, Coin>();
            try
            {
				this.current_height = Blockchain.current() != null ? Blockchain.current().headerHeight() + 1 : 0;
			}
            catch (Exception ex)
            {
            	this.current_height = 0;
			}
            buildDatabase();
            saveStoredData("PasswordHash", Digest.sha256(passwordKey));
            saveStoredData("IV", iv);
            saveStoredData("MasterKey", AES.encrypt(masterKey, passwordKey, iv));
            saveStoredData("Version", new byte[] { 0, 7, 0, 0 });
            saveStoredData("Height", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(current_height).array());
            //ProtectedMemory.Protect(masterKey, MemoryProtectionScope.SameProcess);
        }
        else
        {
            byte[] passwordHash = loadStoredData("PasswordHash");
            if (passwordHash != null && !Arrays.equals(passwordHash, Digest.sha256(passwordKey)))
                throw new BadPaddingException();
            this.iv = loadStoredData("IV");
			this.masterKey = AES.decrypt(loadStoredData("MasterKey"), passwordKey, iv);
	        //ProtectedMemory.Protect(masterKey, MemoryProtectionScope.SameProcess);
            this.accounts = Arrays.stream(loadAccounts()).collect(Collectors.toMap(p -> p.publicKeyHash, p -> p));
//          this.contracts = Arrays.stream(loadContracts()).collect(Collectors.toMap(p -> p.publicKeyHash, p -> p));
            /*
             *  ********************ChangeLog**************************
             *  date:20161024
             *  auth:tsh
             *  desp:change method to get Constracts.Key,cause wallet.addContract
             *  
             */
            this.contracts = Arrays.stream(loadContracts()).collect(Collectors.toMap(p -> p.scriptHash(), p -> p));
            this.coins = new TrackableCollection<TransactionInput, Coin>(loadCoins());
            this.current_height = ByteBuffer.wrap(loadStoredData("Height")).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        Arrays.fill(passwordKey, (byte) 0);
        this.thread = new Thread(this::processBlocks);
        this.thread.setDaemon(true);
        this.thread.setName("Wallet.ProcessBlocks");
        this.thread.start();
    }

    protected Wallet(String path, String password, boolean create) throws BadPaddingException, IllegalBlockSizeException
    {
        this(path, AES.generateKey(password), create);
    }

    // TODO
    //protected Wallet(String path, SecureString password, boolean create)
    //{
        //this(path, password.ToAesKey(), create)
    //}

    public void addContract(Contract contract)
    {
        synchronized (accounts)
        {
            if (!accounts.containsKey(contract.publicKeyHash))
                throw new RuntimeException();
            synchronized(contracts)
            {
                contracts.put(contract.scriptHash(), contract);
            }
        }
    }

    protected void buildDatabase()
    {
    }

    public static Fixed8 calculateClaimAmount(Iterable<TransactionInput> inputs)
    {
//        if (!Blockchain.Default.Ability.HasFlag(BlockchainAbility.UnspentIndexes))
//            throw new NotSupportedException();
//        List<Claimable> unclaimed = new List<Claimable>();
        // TODO
//        for (var group : inputs.GroupBy(p => p.PrevHash))
//        {
//            Dictionary<ushort, Claimable> claimable = Blockchain.Default.GetUnclaimed(group.Key);
//            if (claimable == null || claimable.Count == 0)
//                throw new ArgumentException();
//            for (TransactionInput claim : group)
//            {
//                if (!claimable.ContainsKey(claim.PrevIndex))
//                    throw new ArgumentException();
//                unclaimed.Add(claimable[claim.PrevIndex]);
//            }
//        }
        Fixed8 amount_claimed = Fixed8.ZERO;
//        for (var group : unclaimed.GroupBy(p => new { p.StartHeight, p.EndHeight }))
//        {
//            uint amount = 0;
//            uint ustart = group.Key.StartHeight / Blockchain.DecrementInterval;
//            if (ustart < Blockchain.MintingAmount.length)
//            {
//                uint istart = group.Key.StartHeight % Blockchain.DecrementInterval;
//                uint uend = group.Key.EndHeight / Blockchain.DecrementInterval;
//                uint iend = group.Key.EndHeight % Blockchain.DecrementInterval;
//                if (uend >= Blockchain.MintingAmount.length)
//                {
//                    uend = (uint)Blockchain.MintingAmount.length;
//                    iend = 0;
//                }
//                if (iend == 0)
//                {
//                    uend--;
//                    iend = Blockchain.DecrementInterval;
//                }
//                while (ustart < uend)
//                {
//                    amount += (Blockchain.DecrementInterval - istart) * Blockchain.MintingAmount[ustart];
//                    ustart++;
//                    istart = 0;
//                }
//                amount += (iend - istart) * Blockchain.MintingAmount[ustart];
//            }
//            amount += (uint)(Blockchain.Default.GetSysFeeAmount(group.Key.EndHeight - 1) - (group.Key.StartHeight == 0 ? 0 : Blockchain.Default.GetSysFeeAmount(group.Key.StartHeight - 1)));
//            amount_claimed += group.Sum(p => p.Value) / 100000000 * amount;
//        }
        return amount_claimed;
    }

    public boolean changePassword(String password_old, String password_new)
    {
        byte[] passwordHash = loadStoredData("PasswordHash");
        if (!Arrays.equals(passwordHash, Digest.sha256(AES.generateKey(password_old))))
            return false;
        byte[] passwordKey = AES.generateKey(password_new);
        //using (new ProtectedMemoryContext(masterKey, MemoryProtectionScope.SameProcess))
        {
            try
            {
                saveStoredData("MasterKey", AES.encrypt(masterKey, passwordKey, iv));
                return true;
            }
            finally
            {
                Arrays.fill(passwordKey, (byte)0);
            }
        }
    }

    @Override
    public void close()
    {
        isrunning = false;
        if (thread.getState() != State.NEW)
        {
			try
			{
				thread.join();
			}
			catch (InterruptedException ex)
			{
			}
        }
    }
    
    public boolean containsAccount(ECPoint publicKey)
    {
        return containsAccount(Script.toScriptHash(publicKey.getEncoded(true)));
    }

    public boolean containsAccount(UInt160 publicKeyHash)
    {
        synchronized (accounts)
        {
            return accounts.containsKey(publicKeyHash);
        }
    }

    public boolean containsAddress(UInt160 scriptHash)
    {
        synchronized (contracts)
        {
            return contracts.containsKey(scriptHash);
        }
    }

    public Account createAccount()
    {
        byte[] privateKey = ECC.generateKey();
        Account account = createAccount(privateKey);
        Arrays.fill(privateKey, (byte) 0);
        return account;
    }

    public Account createAccount(byte[] privateKey)
    {
        Account account = new Account(privateKey);
        synchronized (accounts)
        {
            accounts.put(account.publicKeyHash, account);
        }
        return account;
    }

    protected byte[] decryptPrivateKey(byte[] encryptedPrivateKey) throws IllegalBlockSizeException, BadPaddingException
    {
        if (encryptedPrivateKey == null) throw new NullPointerException("encryptedPrivateKey");
        if (encryptedPrivateKey.length != 112) throw new IllegalArgumentException();
        //using (new ProtectedMemoryContext(masterKey, MemoryProtectionScope.SameProcess))
        {
            return AES.decrypt(encryptedPrivateKey, masterKey, iv);
        }
    }

    public boolean deleteAccount(UInt160 publicKeyHash)
    {
        synchronized (accounts)
        {
            synchronized (contracts)
            {
                for (Contract contract : contracts.values().stream().filter(p -> p.publicKeyHash == publicKeyHash).toArray(Contract[]::new))
                {
                    deleteContract(contract.scriptHash());
                }
            }
            return accounts.remove(publicKeyHash) != null;
        }
    }

    public boolean deleteContract(UInt160 scriptHash)
    {
        synchronized (contracts)
        {
            synchronized (coins)
            {
            	Iterator<Coin> iterator = coins.iterator();
            	while (iterator.hasNext())
            		if (iterator.next().scriptHash.equals(scriptHash))
            			iterator.remove();
                coins.commit();
                return contracts.remove(scriptHash) != null;
            }
        }
    }

    protected byte[] encryptPrivateKey(byte[] decryptedPrivateKey)
    {
        //using (new ProtectedMemoryContext(masterKey, MemoryProtectionScope.SameProcess))
        {
            return AES.encrypt(decryptedPrivateKey, masterKey, iv);
        }
    }

    public Coin[] findCoins()
    {
        synchronized (coins)
        {
            return coins.stream().filter(p -> p.getState() == CoinState.Unconfirmed || p.getState() == CoinState.Unspent).toArray(Coin[]::new);
        }
    }

    public Coin[] findUnspentCoins()
    {
        synchronized (coins)
        {
            return coins.stream().filter(p -> p.getState() == CoinState.Unspent).toArray(Coin[]::new);
        }
    }

    public Coin[] findUnspentCoins(UInt256 asset_id, Fixed8 amount)
    {
        return findUnspentCoins(asset_id, amount, null);
    }
    
    public Coin[] findUnspentCoins(UInt256 asset_id, Fixed8 amount, UInt160 from)
    {
    	synchronized (coins)
    	{
    		Stream<Coin> unspents = coins.stream().filter(p -> p.getState() == CoinState.Unspent);
    		if (from != null)
    			unspents = unspents.filter(p -> p.scriptHash.equals(from));
    		return findUnspentCoins(unspents, asset_id, amount);
    	}
    }

    protected static Coin[] findUnspentCoins(Stream<Coin> unspents, UInt256 asset_id, Fixed8 amount)
    {
        Coin[] unspents_asset = unspents.filter(p -> p.assetId.equals(asset_id)).toArray(Coin[]::new);
        Fixed8 sum = Fixed8.sum(unspents_asset, p -> p.value);
        if (sum.compareTo(amount) < 0) return null;
        if (sum.equals(amount)) return unspents_asset;
        Arrays.sort(unspents_asset, (a, b) -> -a.value.compareTo(b.value));
        int i = 0;
        while (unspents_asset[i].value.compareTo(amount) <= 0)
            amount = amount.subtract(unspents_asset[i++].value);
        if (amount.equals(Fixed8.ZERO))
        {
            return Arrays.stream(unspents_asset).limit(i).toArray(Coin[]::new);
        }
        else
        {
        	Coin[] result = new Coin[i + 1];
        	System.arraycopy(unspents_asset, 0, result, 0, i);
        	for (int j = unspents_asset.length - 1; j >= 0; j--)
        		if (unspents_asset[j].value.compareTo(amount) >= 0)
        		{
        			result[i] = unspents_asset[j];
        			break;
        		}
        	return result;
        }
    }

    public Account getAccount(ECPoint publicKey)
    {
        return getAccount(Script.toScriptHash(publicKey.getEncoded(true)));
    }

    public Account getAccount(UInt160 publicKeyHash)
    {
        synchronized (accounts)
        {
            if (!accounts.containsKey(publicKeyHash)) return null;
            return accounts.get(publicKeyHash);
        }
    }

    public Account getAccountByScriptHash(UInt160 scriptHash)
    {
        synchronized (accounts)
        {
            synchronized (contracts)
            {
                if (!contracts.containsKey(scriptHash)) return null;
                return accounts.get(contracts.get(scriptHash).publicKeyHash);
            }
        }
    }

    public Account[] getAccounts()
    {
        synchronized (accounts)
        {
        	return accounts.values().toArray(new Account[accounts.size()]);
        }
    }

    public UInt160[] getAddresses()
    {
        synchronized (contracts)
        {
        	return contracts.keySet().toArray(new UInt160[contracts.size()]);
        }
    }

    public Fixed8 getAvailable(UInt256 asset_id)
    {
        synchronized (coins)
        {
        	return Fixed8.sum(coins.stream().filter(p -> p.getState() == CoinState.Unspent && p.assetId.equals(asset_id)).toArray(Coin[]::new), p -> p.value);
        }
    }

    public Fixed8 getBalance(UInt256 asset_id)
    {
        synchronized (coins)
        {
        	return Fixed8.sum(coins.stream().filter(p -> (p.getState() == CoinState.Unconfirmed || p.getState() == CoinState.Unspent) && p.assetId.equals(asset_id)).toArray(Coin[]::new), p -> p.value);
        }
    }

    public UInt160 getChangeAddress()
    {
        synchronized (contracts)
        {
        	return contracts.values().stream().filter(p -> p.isStandard()).findAny().map(p -> p.scriptHash()).orElse(contracts.keySet().stream().findAny().get());
        }
    }

    public Contract getContract(UInt160 scriptHash)
    {
        synchronized (contracts)
        {
            if (!contracts.containsKey(scriptHash)) return null;
            return contracts.get(scriptHash);
        }
    }

    public Contract[] getContracts()
    {
        synchronized (contracts)
        {
        	return contracts.values().toArray(new Contract[contracts.size()]);
        }
    }

    public Contract[] getContracts(UInt160 publicKeyHash)
    {
        synchronized (contracts)
        {
        	return contracts.values().stream().filter(p -> p.publicKeyHash.equals(publicKeyHash)).toArray(Contract[]::new);
        }
    }

    public static byte[] getPrivateKeyFromWIF(String wif)
    {
        if (wif == null) throw new NullPointerException();
        byte[] data = Base58.decode(wif);
        if (data.length != 38 || data[0] != (byte)0x80 || data[33] != 0x01)
            throw new IllegalArgumentException();
        byte[] checksum = Digest.sha256(Digest.sha256(data, 0, data.length - 4));
        for (int i = 0; i < 4; i++)
        	if (data[data.length - 4 + i] != checksum[i])
        		throw new IllegalArgumentException();
        byte[] privateKey = new byte[32];
        System.arraycopy(data, 1, privateKey, 0, privateKey.length);
        Arrays.fill(data, (byte) 0);
        return privateKey;
    }

    public Coin[] getUnclaimedCoins()
    {
        synchronized (coins)
        {
            return coins.stream().filter(p -> p.getState() == CoinState.Spent && p.assetId.equals(Blockchain.ANTSHARE.hash())).toArray(Coin[]::new);
        }
    }

    //TODO
//    public Account Import(X509Certificate2 cert)
//    {
//        byte[] privateKey = null;
//        using (ECDsaCng ecdsa = (ECDsaCng)cert.GetECDsaPrivateKey())
//        {
//            privateKey = ecdsa.Key.Export(CngKeyBlobFormat.EccPrivateBlob);
//        }
//        Account account = createAccount(privateKey);
//        Arrays.fill(privateKey, (byte) 0);
//        return account;
//    }

    public Account importAccount(String wif)
    {
        byte[] privateKey = getPrivateKeyFromWIF(wif);
        Account account = createAccount(privateKey);
        Arrays.fill(privateKey, (byte) 0);
        return account;
    }
    
    protected boolean isWalletTransaction(Transaction tx)
    {
    	synchronized (contracts)
        {
            if (Arrays.stream(tx.outputs).anyMatch(p -> contracts.containsKey(p.scriptHash)))
                return true;
            if (Arrays.stream(tx.scripts).anyMatch(p -> contracts.containsKey(Script.toScriptHash(p.redeemScript))))
                return true;
        }
        return false;
    }

    protected abstract Account[] loadAccounts();

    protected abstract Coin[] loadCoins();

    protected abstract Contract[] loadContracts();

    protected abstract byte[] loadStoredData(String name);
    
    public <T extends Transaction> T makeTransaction(T tx, Fixed8 fee)
    {
    	return makeTransaction(tx, fee, null);
    }

    public <T extends Transaction> T makeTransaction(T tx, Fixed8 fee, UInt160 from)
    {
        if (tx.outputs == null) throw new IllegalArgumentException();
        if (tx.attributes == null) tx.attributes = new TransactionAttribute[0];
        fee = fee.add(tx.systemFee());
        Map<UInt256, Fixed8> pay_total = Arrays.stream(tx instanceof IssueTransaction ? new TransactionOutput[0] : tx.outputs).collect(Collectors.groupingBy(p -> p.assetId)).entrySet().stream().collect(Collectors.toMap(p -> p.getKey(), p -> Fixed8.sum(p.getValue().toArray(new TransactionOutput[0]), o -> o.value)));
        if (fee.compareTo(Fixed8.ZERO) > 0)
        {
        	Fixed8 value = pay_total.get(Blockchain.ANTCOIN.hash());
        	if (value == null) value = Fixed8.ZERO;
        	value = value.add(fee);
        	pay_total.put(Blockchain.ANTCOIN.hash(), value);
        }
        Map<UInt256, Coin[]> pay_coins = pay_total.entrySet().stream().collect(Collectors.toMap(p -> p.getKey(), p -> findUnspentCoins(p.getKey(), p.getValue(), from)));
        if (pay_coins.values().stream().anyMatch(p -> p == null)) return null;
        Map<UInt256, Fixed8> input_sum = pay_coins.entrySet().stream().collect(Collectors.toMap(p -> p.getKey(), p -> Fixed8.sum(p.getValue(), c -> c.value)));
        UInt160 change_address = from == null ? getChangeAddress() : from;
        List<TransactionOutput> outputs_new = new ArrayList<TransactionOutput>(Arrays.asList(tx.outputs));
        for (Entry<UInt256, Fixed8> entry : input_sum.entrySet())
        {
        	Fixed8 pay = pay_total.get(entry.getKey());
            if (entry.getValue().compareTo(pay) > 0)
            {
            	TransactionOutput output = new TransactionOutput();
            	output.assetId = entry.getKey();
            	output.value = entry.getValue().subtract(pay);
            	output.scriptHash = change_address;
            	outputs_new.add(output);
            }
        }
        tx.inputs = pay_coins.values().stream().flatMap(p -> Arrays.stream(p)).map(p -> p.input).toArray(TransactionInput[]::new);
        tx.outputs = outputs_new.toArray(new TransactionOutput[outputs_new.size()]);
        return tx;
    }

    protected abstract void onProcessNewBlock(Block block, Coin[] added, Coin[] changed, Coin[] deleted);
    protected abstract void onSaveTransaction(Transaction tx, Coin[] added, Coin[] changed);

    private void processBlocks()
    {
        while (isrunning)
        {
        	Blockchain blockchain = Blockchain.current();
            while (true)
            {
            	int height;
            	try
            	{
					height = blockchain == null ? 0 : blockchain.height();
				}
            	catch (Exception ex)
            	{
					break;
				}
            	if (current_height > height || !isrunning)
            		break;
                synchronized (syncroot)
                {
                    Block block;
					try
					{
						block = blockchain.getBlock(current_height);
					}
					catch (Exception ex)
					{
						break;
					}
                    if (block != null) processNewBlock(block);
                }
            }
            try
            {
	            for (int i = 0; i < 20 && isrunning; i++)
	                Thread.sleep(100);
            }
            catch (InterruptedException ex)
            {
            	break;
            }
        }
    }
    private void processNewBlock(Block block)
    {
        Coin[] changeset;
        synchronized (contracts)
        {
            synchronized (coins)
            {
                for (Transaction tx : block.transactions)
                {
                    for (/*ushort*/int index = 0; index < tx.outputs.length; index++)
                    {
                        TransactionOutput output = tx.outputs[index];
                        if (contracts.containsKey(output.scriptHash))
                        {
                            TransactionInput key = new TransactionInput();
                            key.prevHash = tx.hash();
                            key.prevIndex = (short)index;
                            if (coins.containsKey(key))
                            {
                                coins.get(key).setState(CoinState.Unspent);
                            }
                            else
                            {
                            	Coin coin = new Coin();
                            	coin.input = key;
                            	coin.assetId = output.assetId;
                            	coin.value = output.value;
                            	coin.scriptHash = output.scriptHash;
                            	coin.setState(CoinState.Unspent);
                                coins.add(coin);
                            }
                        }
                    }
                }
                for (Transaction tx : block.transactions)
                {
                    for (TransactionInput input : tx.getAllInputs().toArray(TransactionInput[]::new))
                    {
                        if (coins.containsKey(input))
                        {
                        	Coin coin = coins.get(input);
                            if (coin.assetId.equals(Blockchain.ANTSHARE.hash()))
                            	coin.setState(CoinState.Spent);
                            else
                                coins.remove(input);
                        }
                    }
                }
                for (ClaimTransaction tx : Arrays.stream(block.transactions).filter(p -> p.type == TransactionType.ClaimTransaction).toArray(ClaimTransaction[]::new))
                {
                    for (TransactionInput claim : tx.claims)
                    {
                        if (coins.containsKey(claim))
                        {
                            coins.remove(claim);
                        }
                    }
                }
//                current_height++;
                changeset = coins.getChangeSet(Coin[]::new);
                Coin[] added = Arrays.stream(changeset).filter(p -> p.getTrackState() == TrackState.Added).toArray(Coin[]::new);
                Coin[] changed = Arrays.stream(changeset).filter(p -> p.getTrackState() == TrackState.Changed).toArray(Coin[]::new);
                Coin[] deleted = Arrays.stream(changeset).filter(p -> p.getTrackState() == TrackState.Deleted).toArray(Coin[]::new);
                onProcessNewBlock(block, added, changed, deleted);
                coins.commit();
                current_height++;
            }
        }
        // TODO
//        if (changeset.length > 0)
//            BalanceChanged?.Invoke(this, EventArgs.Empty);
    }

	public void rebuild()
    {
        synchronized (syncroot)
        {
            synchronized (coins)
            {
                coins.clear();
                coins.commit();
                current_height = 0;
            }
        }
    }

    protected abstract void saveStoredData(String name, byte[] value);

    public boolean saveTransaction(Transaction tx)
    {
        Coin[] changeset;
        synchronized (contracts)
        {
            synchronized (coins)
            {
                if (tx.getAllInputs().anyMatch(p -> !coins.containsKey(p) || coins.get(p).getState() != CoinState.Unspent))
                    return false;
                for (TransactionInput input : tx.getAllInputs().toArray(TransactionInput[]::new))
                    coins.get(input).setState(CoinState.Spending);
                for (/*ushort*/int i = 0; i < tx.outputs.length; i++)
                {
                    if (contracts.containsKey(tx.outputs[i].scriptHash))
                    {
                    	Coin coin = new Coin();
                    	coin.input = new TransactionInput();
                    	coin.input.prevHash = tx.hash();
                    	coin.input.prevIndex = (short)i;
                    	coin.assetId = tx.outputs[i].assetId;
                    	coin.value = tx.outputs[i].value;
                    	coin.scriptHash = tx.outputs[i].scriptHash;
                    	coin.setState(CoinState.Unconfirmed);
                    	coins.add(coin);
                    }
                }
                changeset = coins.getChangeSet(Coin[]::new);
                if (changeset.length > 0)
                {
                	Coin[] added = Arrays.stream(changeset).filter(p -> p.getTrackState() == TrackState.Added).toArray(Coin[]::new);
                	Coin[] changed = Arrays.stream(changeset).filter(p -> p.getTrackState() == TrackState.Changed).toArray(Coin[]::new);
                    onSaveTransaction(tx, added, changed);
                    coins.commit();
                }
            }
        }
//        if (changeset.length > 0)
//            BalanceChanged?.Invoke(this, EventArgs.Empty);
        return true;
    }

    public boolean sign(SignatureContext context)
    {
        boolean fSuccess = false;
        for (UInt160 scriptHash : context.scriptHashes)
        {
            Contract contract = getContract(scriptHash);
            if (contract == null) continue;
            Account account = getAccountByScriptHash(scriptHash);
            if (account == null) continue;
            byte[] signature = context.signable.sign(account);
            fSuccess |= context.add(contract, account.publicKey, signature);
        }
        return fSuccess;
    }

    public static String toAddress(UInt160 scriptHash)
    {
    	byte[] data = new byte[25];
    	data[0] = COIN_VERSION;
    	System.arraycopy(scriptHash.toArray(), 0, data, 1, 20);
    	byte[] checksum = Digest.sha256(Digest.sha256(data, 0, 21));
    	System.arraycopy(checksum, 0, data, 21, 4);
        return Base58.encode(data);
    }

    public static UInt160 toScriptHash(String address)
    {
        byte[] data = Base58.decode(address);
        if (data.length != 25)
            throw new IllegalArgumentException();
        if (data[0] != COIN_VERSION)
            throw new IllegalArgumentException();
        byte[] checksum = Digest.sha256(Digest.sha256(data, 0, 21));
        for (int i = 0; i < 4; i++)
        	if (data[data.length - 4 + i] != checksum[i])
        		throw new IllegalArgumentException();
        byte[] buffer = new byte[20];
        System.arraycopy(data, 1, buffer, 0, 20);
        return new UInt160(buffer);
    }
}
