package AntShares.Core;

import java.io.IOException;

import AntShares.*;
import AntShares.IO.*;

public class IssueTransaction extends Transaction
{
	public int nonce; // unsigned int
	
	public IssueTransaction()
	{
		super(TransactionType.IssueTransaction);
	}
	
	@Override
	protected void deserializeExclusiveData(BinaryReader reader) throws IOException
	{
		nonce = reader.readInt();
	}
	
	@Override
	public UInt160[] getScriptHashesForVerifying()
	{
		//TODO
		return super.getScriptHashesForVerifying();
	}
	
	@Override
	protected void serializeExclusiveData(BinaryWriter writer) throws IOException
	{
		writer.writeInt(nonce);
	}
	
	@Override
	public Fixed8 systemFee()
	{
		//TODO: mainnet
		return Fixed8.ZERO;
	}
	
	@Override
	public boolean verify()
	{
		//TODO
		return super.verify();
	}
}
