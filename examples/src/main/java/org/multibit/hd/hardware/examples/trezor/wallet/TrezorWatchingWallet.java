package org.multibit.hd.hardware.examples.trezor.wallet;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.Uninterruptibles;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.wallet.KeyChain;
import org.multibit.hd.hardware.core.HardwareWalletClient;
import org.multibit.hd.hardware.core.HardwareWalletService;
import org.multibit.hd.hardware.core.concurrent.SafeExecutors;
import org.multibit.hd.hardware.core.events.HardwareWalletEvent;
import org.multibit.hd.hardware.core.messages.MainNetAddress;
import org.multibit.hd.hardware.core.messages.PinMatrixRequest;
import org.multibit.hd.hardware.core.wallets.HardwareWallets;
import org.multibit.hd.hardware.trezor.clients.TrezorHardwareWalletClient;
import org.multibit.hd.hardware.trezor.utils.TrezorMessageUtils;
import org.multibit.hd.hardware.trezor.wallets.v1.TrezorV1HidHardwareWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>Create a Bitcoinj watching wallet based on a deterministic hierarchy provided by a Trezor</p>
 * <p>Requires Trezor V1 production device plugged into a USB HID interface.</p>
 * <p>This example demonstrates the message sequence to get a Bitcoinj deterministic hierarchy
 * from a Trezor that has an active wallet to enable a "watching wallet" to be created.</p>
 * <p/>
 * <h3>Only perform this example on a Trezor that you are using for test and development!</h3>
 * <h3>Do not send funds to any addresses generated from this xpub unless you have a copy of the seed phrase written down!</h3>
 *
 * @since 0.0.1
 *  
 */
public class TrezorWatchingWallet {

  private static final Logger log = LoggerFactory.getLogger(TrezorWatchingWallet.class);

  private static final String START_OF_REPLAY_PERIOD = "2014-11-01 00:00:00";
  private static Date replayDate;

  private static PeerGroup peerGroup;

  private static BlockChain blockChain;

  private static NetworkParameters networkParameters;

  public static final String SPV_BLOCKCHAIN_SUFFIX = ".spvchain";
  public static final String CHECKPOINTS_SUFFIX = ".checkpoints";

  private HardwareWalletService hardwareWalletService;

  private ListeningExecutorService walletService = SafeExecutors.newSingleThreadExecutor("wallet-service");
  private Wallet watchingWallet;

  /**
   * <p>Main entry point to the example</p>
   *
   * @param args None required
   *
   * @throws Exception If something goes wrong
   */
  public static void main(String[] args) throws Exception {

    java.text.SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    java.util.Calendar cal = Calendar.getInstance(new SimpleTimeZone(0, "GMT"));
    format.setCalendar(cal);
    replayDate = format.parse(START_OF_REPLAY_PERIOD);
    log.debug("Replay for this watching wallet will be performed from {}", replayDate.toString());

    // All the work is done in the class
    TrezorWatchingWallet example = new TrezorWatchingWallet();

    example.executeExample();

  }

  /**
   * Execute the example
   */
  public void executeExample() {

    // Use factory to statically bind the specific hardware wallet
    TrezorV1HidHardwareWallet wallet = HardwareWallets.newUsbInstance(
      TrezorV1HidHardwareWallet.class,
      Optional.<Integer>absent(),
      Optional.<Integer>absent(),
      Optional.<String>absent()
    );

    // Wrap the hardware wallet in a suitable client to simplify message API
    HardwareWalletClient client = new TrezorHardwareWalletClient(wallet);

    // Wrap the client in a service for high level API suitable for downstream applications
    hardwareWalletService = new HardwareWalletService(client);

    // Register for the high level hardware wallet events
    HardwareWalletService.hardwareWalletEventBus.register(this);

    hardwareWalletService.start();

    // Simulate the main thread continuing with other unrelated work
    // We don't terminate main since we're using safe executors
    Uninterruptibles.sleepUninterruptibly(1, TimeUnit.HOURS);

  }

  /**
   * <p>Downstream consumer applications should respond to hardware wallet events</p>
   *
   * @param event The hardware wallet event indicating a state change
   */
  @Subscribe
  public void onHardwareWalletEvent(HardwareWalletEvent event) {

    log.debug("Received hardware event: '{}'.{}", event.getEventType().name(), event.getMessage());

    switch (event.getEventType()) {
      case SHOW_DEVICE_FAILED:
        // Treat as end of example
        System.exit(0);
        break;
      case SHOW_DEVICE_DETACHED:
        // Can simply wait for another device to be connected again
        break;
      case SHOW_DEVICE_READY:
        if (hardwareWalletService.isWalletPresent()) {

          log.debug("Wallet is present. Requesting an address...");

          // Request the extended public key for the given account
          hardwareWalletService.requestDeterministicHierarchy(
            Lists.newArrayList(
              new ChildNumber(44 | ChildNumber.HARDENED_BIT),
              ChildNumber.ZERO_HARDENED,
              ChildNumber.ZERO_HARDENED
            ));

        } else {
          log.info("You need to have created a wallet before running this example");
        }

        break;
      case DETERMINISTIC_HIERARCHY:

        // Exit quickly from the event thread
        walletService.submit(new Runnable() {
          @Override
          public void run() {

            // Parent key should be M/44'/0'/0'
            DeterministicKey parentKey = hardwareWalletService.getContext().getDeterministicKey().get();
            log.info("Parent key path: {}", parentKey.getPathAsString());

            // Verify the deterministic hierarchy can derive child keys
            // In this case 0/0 from a parent of M/44'/0'/0'
            final DeterministicHierarchy hierarchy = hardwareWalletService.getContext().getDeterministicHierarchy().get();

            // Create a watching wallet
            createWatchingWallet(hierarchy);
          }
        });

        break;
      case ADDRESS:

        MainNetAddress changeAddress = (MainNetAddress) event.getMessage().get();

        // Received an address from the device (must be part of our spend)
        // Build a spend transaction
        Wallet.SendRequest sendRequest = Wallet.SendRequest.to(changeAddress.getAddress().get(), Coin.valueOf(100_000));
        sendRequest.missingSigsMode = Wallet.MissingSigsMode.USE_OP_ZERO;
        try {
          watchingWallet.completeTx(sendRequest);
        } catch (InsufficientMoneyException e) {
          log.error("Insufficient funds. Require at least 100 000 satoshis");
          System.exit(-1);
          return;
        }
        final Transaction spendToChangeTx = sendRequest.tx;

        // Create a map of transaction inputs to addresses (we expect funds as a Tx with single input to 0/0/0)
        Map<Integer, List<Integer>> receivingAddressPathMap = buildReceivingAddressPathMap(spendToChangeTx);
        hardwareWalletService.signTx(spendToChangeTx, receivingAddressPathMap);
        break;

      case SHOW_PIN_ENTRY:
        // Device requires the current PIN to proceed
        PinMatrixRequest request = (PinMatrixRequest) event.getMessage().get();
        Scanner keyboard = new Scanner(System.in);
        String pin;
        switch (request.getPinMatrixRequestType()) {
          case CURRENT:
            System.err.println(
              "Recall your PIN (e.g. '1').\n" +
                "Look at the device screen and type in the numerical position of each of the digits\n" +
                "with 1 being in the bottom left and 9 being in the top right (numeric keypad style) then press ENTER."
            );
            pin = keyboard.next();
            hardwareWalletService.providePIN(pin);
            break;
        }
        break;
      case SHOW_OPERATION_SUCCEEDED:

        // Successful signature

        // Trezor will provide a signed serialized transaction
        byte[] deviceTxPayload = hardwareWalletService.getContext().getSerializedTx().toByteArray();

        byte[] signature0 = hardwareWalletService.getContext().getSignatures().get(0);

        try {
          log.info("DeviceTx payload:\n{}", Utils.HEX.encode(deviceTxPayload));
          log.info("DeviceTx signature0:\n{}", Utils.HEX.encode(signature0));

          // Load deviceTx
          Transaction deviceTx = new Transaction(MainNetParams.get(), deviceTxPayload);

          log.info("deviceTx:\n{}", deviceTx.toString());
          log.info("Use http://blockchain.info/pushtx to broadcast this transaction to the Bitcoin network");
          // The deserialized transaction
          log.info("DeviceTx info:\n{}", deviceTx.toString());

          log.info("DeviceTx pushtx:\n{}", Utils.HEX.encode(deviceTx.bitcoinSerialize()));

        } catch (Exception e) {
          log.error("DeviceTx FAILED.", e);
        }

        // Treat as end of example
        System.exit(0);
        break;

      case SHOW_OPERATION_FAILED:
        log.error(event.getMessage().toString());
        // Treat as end of example
        System.exit(-1);
        break;
      default:
        // Ignore
    }
  }

  private Map<Integer, List<Integer>> buildReceivingAddressPathMap(Transaction spendToChangeTx) {
    Map<Integer, List<Integer>> receivingAddressPathMap = Maps.newHashMap();
    Address address_0_0 = watchingWallet.getKeyByPath(Lists.newArrayList(
      new ChildNumber(44 | ChildNumber.HARDENED_BIT),
      ChildNumber.ZERO_HARDENED,
      ChildNumber.ZERO_HARDENED,
      ChildNumber.ZERO,
      ChildNumber.ZERO
    )).toAddress(networkParameters);

    Address address_1_0 = watchingWallet.getKeyByPath(Lists.newArrayList(
      new ChildNumber(44 | ChildNumber.HARDENED_BIT),
      ChildNumber.ZERO_HARDENED,
      ChildNumber.ZERO_HARDENED,
      ChildNumber.ONE,
      ChildNumber.ZERO
    )).toAddress(networkParameters);


    for (int i = 0; i < spendToChangeTx.getInputs().size(); i++) {
      TransactionInput input = spendToChangeTx.getInput(i);
      if (input.getConnectedOutput() != null) {
        Address address = input.getConnectedOutput().getAddressFromP2PKHScript(networkParameters);
        if (address_0_0.equals(address)) {
          receivingAddressPathMap.put(i, TrezorMessageUtils.buildAddressN(0, KeyChain.KeyPurpose.RECEIVE_FUNDS, 0));
        } else if (address_1_0.equals(address)) {
          receivingAddressPathMap.put(i, TrezorMessageUtils.buildAddressN(0, KeyChain.KeyPurpose.CHANGE, 0));
        } else {
          log.info("Unknown receiving address: {}", address.toString());
        }
      }

    }
    return receivingAddressPathMap;
  }

  /**
   * @param hierarchy The deterministic hierarchy that forms the first node in the watching wallet
   */
  private void createWatchingWallet(DeterministicHierarchy hierarchy) {

    // Date format is UTC with century, T time separator and Z for UTC timezone.
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
    formatter.setTimeZone(TimeZone.getTimeZone("UTC"));

    networkParameters = NetworkParameters.fromID(NetworkParameters.ID_MAINNET);

    final File walletEnvironmentDirectory;
    try {
      walletEnvironmentDirectory = createWalletEnvironment();
    } catch (IOException e) {
      handleError(e);
      return;
    }

    // Create wallet
    String walletPath = walletEnvironmentDirectory.getAbsolutePath() + File.separator + "watching.wallet";

    try {

      // Derive the first key
      DeterministicKey key_0_0 = hierarchy.deriveChild(
        Lists.newArrayList(
          ChildNumber.ZERO
        ),
        true,
        true,
        ChildNumber.ZERO
      );
      DeterministicKey key_1_0 = hierarchy.deriveChild(
        Lists.newArrayList(
          ChildNumber.ONE
        ),
        true,
        true,
        ChildNumber.ZERO
      );

      // Calculate the pubkeys
      ECKey pubkey_0_0 = ECKey.fromPublicOnly(key_0_0.getPubKey());
      Address address_0_0 = new Address(networkParameters, pubkey_0_0.getPubKeyHash());
      log.debug("Derived 0_0 address '{}'", address_0_0.toString());

      ECKey pubkey_1_0 = ECKey.fromPublicOnly(key_1_0.getPubKey());
      Address address_1_0 = new Address(networkParameters, pubkey_1_0.getPubKeyHash());
      log.debug("Derived 1_0 address '{}'", address_1_0.toString());

      DeterministicKey rootNodePubOnly = hierarchy.getRootKey().getPubOnly();
      log.debug("rootNodePubOnly = " + rootNodePubOnly);
      watchingWallet = Wallet.fromWatchingKey(networkParameters, rootNodePubOnly, (long) (replayDate.getTime() * 0.001), rootNodePubOnly.getPath());
//      TransactionSigner signer = new TrezorTransactionSigner();
//      watchingWallet.addTransactionSigner(signer);

      watchingWallet.saveToFile(new File(walletPath));

      log.debug("Example wallet = \n{}", watchingWallet.toString());

      // Load or create the blockStore..
      log.debug("Loading/creating block store...");
      BlockStore blockStore = createBlockStore(replayDate);
      log.debug("Block store is '" + blockStore + "'");

      log.debug("Creating block chain...");
      blockChain = new BlockChain(networkParameters, blockStore);
      log.debug("Created block chain '" + blockChain + "' with height " + blockChain.getBestChainHeight());
      blockChain.addWallet(watchingWallet);

      log.debug("Creating peer group...");
      createNewPeerGroup(watchingWallet);
      log.debug("Created peer group '" + peerGroup + "'");

      log.debug("Starting peer group...");
      peerGroup.startAsync();
      log.debug("Started peer group.");

    } catch (Exception e) {
      handleError(e);
      return;
    }

    // Wallet environment is now setup and running

    // Wait for a peer connection
    log.debug("Waiting for peer connection. . . ");
    while (peerGroup.getConnectedPeers().isEmpty()) {
      Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
    }
    log.debug("Now online. Downloading block chain...");

    peerGroup.downloadBlockChain();

    log.info("Wallet after sync: {}", watchingWallet.toString());

    // Now that we're synchronized create a spendable transaction
    spendFundsToChangeAddress();

  }

  private void spendFundsToChangeAddress() {

    // Request an address
    hardwareWalletService.requestAddress(0, KeyChain.KeyPurpose.CHANGE, 0, false);

  }

  /**
   * Create a basic wallet environment (block store and checkpoints) in a temporary directory.
   *
   * @return The temporary directory for the wallet environment
   */
  private File createWalletEnvironment() throws IOException {

    File walletDirectory = new File(".");
    String walletDirectoryPath = walletDirectory.getAbsolutePath();

    log.debug("Building Wallet in: '{}'", walletDirectory.getAbsolutePath());

    // Copy in the checkpoints stored in git - this is in src/main/resources
    File checkpoints = new File(walletDirectoryPath + File.separator + "multibit-hardware.checkpoints");

    File source = new java.io.File("./examples/src/main/resources/multibit-hardware.checkpoints");
    log.debug("Using source checkpoints file {}", source.getAbsolutePath());

    copyFile(source, checkpoints);

    checkpoints.deleteOnExit();

    log.debug("Copied checkpoints file to '{}', size {} bytes", checkpoints.getAbsolutePath(), checkpoints.length());

    return walletDirectory;
  }

  /**
   * @param wallet The wallet managing the peers
   */
  private void createNewPeerGroup(Wallet wallet) {

    peerGroup = new PeerGroup(networkParameters, blockChain);
    peerGroup.setUserAgent("TrezorWatchingWallet", "1");

    peerGroup.addPeerDiscovery(new DnsDiscovery(networkParameters));

    peerGroup.addEventListener(new WatchingPeerEventListener(wallet));

    peerGroup.addWallet(wallet);

    peerGroup.recalculateFastCatchupAndFilter(PeerGroup.FilterRecalculateMode.FORCE_SEND_FOR_REFRESH);

  }

  /**
   * @param replayDate The date of the checkpoint file
   *
   * @return The new block store for synchronization
   *
   * @throws BlockStoreException
   * @throws IOException
   */
  private BlockStore createBlockStore(Date replayDate) throws BlockStoreException, IOException {

    BlockStore blockStore = null;

    String filePrefix = "multibit-hardware";
    log.debug("filePrefix = " + filePrefix);

    String blockchainFilename = filePrefix + SPV_BLOCKCHAIN_SUFFIX;
    String checkpointsFilename = filePrefix + CHECKPOINTS_SUFFIX;

    File blockStoreFile = new File(blockchainFilename);
    boolean blockStoreCreatedNew = !blockStoreFile.exists();

    // Ensure there is a checkpoints file.
    File checkpointsFile = new File(checkpointsFilename);

    log.debug("{} SPV block store '{}' from disk", blockStoreCreatedNew ? "Creating" : "Opening", blockchainFilename);
    try {
      blockStore = new SPVBlockStore(networkParameters, blockStoreFile);
    } catch (BlockStoreException bse) {
      handleError(bse);
    }

    // Load the existing checkpoint file and checkpoint from today.
    if (blockStore != null && checkpointsFile.exists()) {
      FileInputStream stream = null;
      try {
        stream = new FileInputStream(checkpointsFile);
        if (replayDate == null) {
          if (blockStoreCreatedNew) {
            // Brand new block store - checkpoint from today. This
            // will go back to the last checkpoint.
            CheckpointManager.checkpoint(networkParameters, stream, blockStore, (new Date()).getTime() / 1000);
          }
        } else {
          // Use checkpoint date (block replay).
          CheckpointManager.checkpoint(networkParameters, stream, blockStore, replayDate.getTime() / 1000);
        }
      } finally {
        if (stream != null) {
          stream.close();
        }
      }
    }

    return blockStore;

  }

  private void copyFile(File from, File to) throws IOException {

    if (!to.exists()) {
      if (!to.createNewFile()) {
        throw new IOException("Could not create '" + to.getAbsolutePath() + "'");
      }
    }

    try (
      FileChannel in = new FileInputStream(from).getChannel();
      FileChannel out = new FileOutputStream(to).getChannel()) {

      out.transferFrom(in, 0, in.size());
    }

  }

  private void handleError(Exception e) {
    log.error("Error creating watching wallet: " + e.getClass().getName() + " " + e.getMessage());

    // Treat as end of example
    System.exit(-1);
  }
}

