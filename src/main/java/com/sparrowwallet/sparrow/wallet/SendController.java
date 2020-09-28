package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.InvalidAddressException;
import com.sparrowwallet.drongo.address.P2PKHAddress;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppController;
import com.sparrowwallet.sparrow.CurrencyRate;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.ExchangeSource;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.controlsfx.validation.decoration.StyleClassValidationDecoration;

import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.stream.Collectors;

public class SendController extends WalletFormController implements Initializable {
    public static final List<Integer> TARGET_BLOCKS_RANGE = List.of(1, 2, 3, 4, 5, 10, 25, 50, 100, 500);

    public static final double FALLBACK_FEE_RATE = 20000d / 1000;

    @FXML
    private CopyableTextField address;

    @FXML
    private TextField label;

    @FXML
    private TextField amount;

    @FXML
    private ComboBox<BitcoinUnit> amountUnit;

    @FXML
    private FiatLabel fiatAmount;

    @FXML
    private Button maxButton;

    @FXML
    private Slider targetBlocks;

    @FXML
    private CopyableLabel feeRate;

    @FXML
    private TextField fee;

    @FXML
    private ComboBox<BitcoinUnit> feeAmountUnit;

    @FXML
    private FiatLabel fiatFeeAmount;

    @FXML
    private FeeRatesChart feeRatesChart;

    @FXML
    private TransactionDiagram transactionDiagram;

    @FXML
    private Button clearButton;

    @FXML
    private Button createButton;

    private final BooleanProperty userFeeSet = new SimpleBooleanProperty(false);

    private final ObjectProperty<UtxoSelector> utxoSelectorProperty = new SimpleObjectProperty<>(null);

    private final ObjectProperty<UtxoFilter> utxoFilterProperty = new SimpleObjectProperty<>(null);

    private final ObjectProperty<WalletTransaction> walletTransactionProperty = new SimpleObjectProperty<>(null);

    private final BooleanProperty insufficientInputsProperty = new SimpleBooleanProperty(false);

    private final ChangeListener<String> amountListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
            if(utxoSelectorProperty.get() instanceof MaxUtxoSelector) {
                utxoSelectorProperty.setValue(null);
            }

            Long recipientValueSats = getRecipientValueSats();
            if(recipientValueSats != null) {
                setFiatAmount(AppController.getFiatCurrencyExchangeRate(), recipientValueSats);
            } else {
                fiatAmount.setText("");
            }

            updateTransaction();
        }
    };

    private final ChangeListener<String> feeListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
            userFeeSet.set(true);
            if(newValue.isEmpty()) {
                fiatFeeAmount.setText("");
            } else {
                setFiatFeeAmount(AppController.getFiatCurrencyExchangeRate(), getFeeValueSats());
            }

            setTargetBlocks(getTargetBlocks());
            updateTransaction();
        }
    };

    private final ChangeListener<Number> targetBlocksListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends Number> observable, Number oldValue, Number newValue) {
            Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
            Integer target = getTargetBlocks();

            if(targetBlocksFeeRates != null) {
                setFeeRate(targetBlocksFeeRates.get(target));
                feeRatesChart.select(target);
            } else {
                feeRate.setText("Unknown");
            }

            Tooltip tooltip = new Tooltip("Target confirmation within " + target + " blocks");
            targetBlocks.setTooltip(tooltip);

            userFeeSet.set(false);
            revalidate(amount, amountListener);
            updateTransaction();
        }
    };

    private ValidationSupport validationSupport;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        address.textProperty().addListener((observable, oldValue, newValue) -> {
            revalidate(amount, amountListener);
            maxButton.setDisable(!isValidRecipientAddress());
            updateTransaction();

            if(validationSupport != null) {
                validationSupport.setErrorDecorationEnabled(true);
            }
        });

        label.textProperty().addListener((observable, oldValue, newValue) -> {
            createButton.setDisable(walletTransactionProperty.get() == null || newValue == null || newValue.isEmpty() || isInsufficientFeeRate());
        });

        amount.setTextFormatter(new CoinTextFormatter());
        amount.textProperty().addListener(amountListener);

        BitcoinUnit unit = Config.get().getBitcoinUnit();
        if(unit == null || unit.equals(BitcoinUnit.AUTO)) {
            unit = getWalletForm().getWallet().getAutoUnit();
        }

        amountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
        amountUnit.valueProperty().addListener((observable, oldValue, newValue) -> {
            Long value = getRecipientValueSats(oldValue);
            if(value != null) {
                DecimalFormat df = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                df.setMaximumFractionDigits(8);
                amount.setText(df.format(newValue.getValue(value)));
            }
        });

        maxButton.setDisable(!isValidRecipientAddress());

        insufficientInputsProperty.addListener((observable, oldValue, newValue) -> {
            revalidate(amount, amountListener);
            revalidate(fee, feeListener);
        });

        targetBlocks.setMin(0);
        targetBlocks.setMax(TARGET_BLOCKS_RANGE.size() - 1);
        targetBlocks.setMajorTickUnit(1);
        targetBlocks.setMinorTickCount(0);
        targetBlocks.setLabelFormatter(new StringConverter<Double>() {
            @Override
            public String toString(Double object) {
                String blocks = Integer.toString(TARGET_BLOCKS_RANGE.get(object.intValue()));
                return (object.intValue() == TARGET_BLOCKS_RANGE.size() - 1) ? blocks + "+" : blocks;
            }

            @Override
            public Double fromString(String string) {
                return (double)TARGET_BLOCKS_RANGE.indexOf(Integer.valueOf(string.replace("+", "")));
            }
        });
        targetBlocks.valueProperty().addListener(targetBlocksListener);

        feeRatesChart.initialize();
        Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
        if(targetBlocksFeeRates != null) {
            feeRatesChart.update(targetBlocksFeeRates);
        } else {
            feeRate.setText("Unknown");
        }

        int defaultTarget = TARGET_BLOCKS_RANGE.get((TARGET_BLOCKS_RANGE.size() / 2) - 1);
        int index = TARGET_BLOCKS_RANGE.indexOf(defaultTarget);
        targetBlocks.setValue(index);
        feeRatesChart.select(defaultTarget);

        fee.setTextFormatter(new CoinTextFormatter());
        fee.textProperty().addListener(feeListener);

        feeAmountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
        feeAmountUnit.valueProperty().addListener((observable, oldValue, newValue) -> {
            Long value = getFeeValueSats(oldValue);
            if(value != null) {
                setFeeValueSats(value);
            }
        });

        userFeeSet.addListener((observable, oldValue, newValue) -> {
            feeRatesChart.select(0);

            Node thumb = getSliderThumb();
            if(thumb != null) {
                if(newValue) {
                    thumb.getStyleClass().add("inactive");
                } else {
                    thumb.getStyleClass().remove("inactive");
                }
            }
        });

        utxoSelectorProperty.addListener((observable, oldValue, utxoSelector) -> {
            updateMaxClearButtons(utxoSelector, utxoFilterProperty.get());
        });

        utxoFilterProperty.addListener((observable, oldValue, utxoFilter) -> {
            updateMaxClearButtons(utxoSelectorProperty.get(), utxoFilter);
        });

        walletTransactionProperty.addListener((observable, oldValue, walletTransaction) -> {
            if(walletTransaction != null) {
                if(getRecipientValueSats() == null || walletTransaction.getRecipientAmount() != getRecipientValueSats()) {
                    setRecipientValueSats(walletTransaction.getRecipientAmount());
                    setFiatAmount(AppController.getFiatCurrencyExchangeRate(), walletTransaction.getRecipientAmount());
                }

                double feeRate = walletTransaction.getFeeRate();
                if(userFeeSet.get()) {
                    setTargetBlocks(getTargetBlocks(feeRate));
                } else {
                    setFeeValueSats(walletTransaction.getFee());
                }

                setFeeRate(feeRate);
            }

            transactionDiagram.update(walletTransaction);
            createButton.setDisable(walletTransaction == null || label.getText().isEmpty() || isInsufficientFeeRate());
        });

        addValidation();
    }

    private void addValidation() {
        validationSupport = new ValidationSupport();
        validationSupport.registerValidator(address, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid Address", !newValue.isEmpty() && !isValidRecipientAddress())
        ));
        validationSupport.registerValidator(label, Validator.combine(
                Validator.createEmptyValidator("Label is required")
        ));
        validationSupport.registerValidator(amount, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Inputs", insufficientInputsProperty.get()),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Value", getRecipientValueSats() != null && getRecipientValueSats() <= getRecipientDustThreshold())
        ));
        validationSupport.registerValidator(fee, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Inputs", userFeeSet.get() && insufficientInputsProperty.get()),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Fee", getFeeValueSats() != null && getFeeValueSats() == 0),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Insufficient Fee Rate", isInsufficientFeeRate())
        ));

        validationSupport.setValidationDecorator(new StyleClassValidationDecoration());
    }

    private void updateTransaction() {
        updateTransaction(false);
    }

    private void updateTransaction(boolean sendAll) {
        try {
            Address recipientAddress = getRecipientAddress();
            long recipientDustThreshold = getRecipientDustThreshold();
            Long recipientAmount = sendAll ? Long.valueOf(recipientDustThreshold + 1) : getRecipientValueSats();
            if(recipientAmount != null && recipientAmount > recipientDustThreshold && (!userFeeSet.get() || (getFeeValueSats() != null && getFeeValueSats() > 0))) {
                Wallet wallet = getWalletForm().getWallet();
                Long userFee = userFeeSet.get() ? getFeeValueSats() : null;
                Integer currentBlockHeight = AppController.getCurrentBlockHeight();
                boolean groupByAddress = Config.get().isGroupByAddress();
                boolean includeMempoolChange = Config.get().isIncludeMempoolChange();
                WalletTransaction walletTransaction = wallet.createWalletTransaction(getUtxoSelectors(), getUtxoFilters(), recipientAddress, recipientAmount, getFeeRate(), getMinimumFeeRate(), userFee, currentBlockHeight, sendAll, groupByAddress, includeMempoolChange);
                walletTransactionProperty.setValue(walletTransaction);
                insufficientInputsProperty.set(false);

                return;
            }
        } catch (InvalidAddressException e) {
            //ignore
        } catch (InsufficientFundsException e) {
            insufficientInputsProperty.set(true);
        }

        walletTransactionProperty.setValue(null);
    }

    private List<UtxoSelector> getUtxoSelectors() throws InvalidAddressException {
        if(utxoSelectorProperty.get() != null) {
            return List.of(utxoSelectorProperty.get());
        }

        Wallet wallet = getWalletForm().getWallet();
        long noInputsFee = wallet.getNoInputsFee(getRecipientAddress(), getFeeRate());
        long costOfChange = wallet.getCostOfChange(getFeeRate(), getMinimumFeeRate());

        return List.of(new BnBUtxoSelector(noInputsFee, costOfChange), new KnapsackUtxoSelector(noInputsFee));
    }

    private List<UtxoFilter> getUtxoFilters() {
        UtxoFilter utxoFilter = utxoFilterProperty.get();
        if(utxoFilter != null) {
            return List.of(utxoFilter);
        }

        return Collections.emptyList();
    }

    private boolean isValidRecipientAddress() {
        try {
            getRecipientAddress();
            return true;
        } catch (InvalidAddressException e) {
            return false;
        }
    }

    private Address getRecipientAddress() throws InvalidAddressException {
        return Address.fromString(getWalletForm().getWallet().getNetwork(), address.getText());
    }

    private Long getRecipientValueSats() {
        return getRecipientValueSats(amountUnit.getSelectionModel().getSelectedItem());
    }

    private Long getRecipientValueSats(BitcoinUnit bitcoinUnit) {
        if(amount.getText() != null && !amount.getText().isEmpty()) {
            double fieldValue = Double.parseDouble(amount.getText().replaceAll(",", ""));
            return bitcoinUnit.getSatsValue(fieldValue);
        }

        return null;
    }

    private void setRecipientValueSats(long recipientValue) {
        amount.textProperty().removeListener(amountListener);
        DecimalFormat df = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(8);
        amount.setText(df.format(amountUnit.getValue().getValue(recipientValue)));
        amount.textProperty().addListener(amountListener);
    }

    private Long getFeeValueSats() {
        return getFeeValueSats(feeAmountUnit.getSelectionModel().getSelectedItem());
    }

    private Long getFeeValueSats(BitcoinUnit bitcoinUnit) {
        if(fee.getText() != null && !fee.getText().isEmpty()) {
            double fieldValue = Double.parseDouble(fee.getText().replaceAll(",", ""));
            return bitcoinUnit.getSatsValue(fieldValue);
        }

        return null;
    }

    private void setFeeValueSats(long feeValue) {
        fee.textProperty().removeListener(feeListener);
        DecimalFormat df = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(8);
        fee.setText(df.format(feeAmountUnit.getValue().getValue(feeValue)));
        fee.textProperty().addListener(feeListener);
        setFiatFeeAmount(AppController.getFiatCurrencyExchangeRate(), feeValue);
    }

    private Integer getTargetBlocks() {
        int index = (int)targetBlocks.getValue();
        return TARGET_BLOCKS_RANGE.get(index);
    }

    private Integer getTargetBlocks(double feeRate) {
        Map<Integer, Double> targetBlocksFeeRates = getTargetBlocksFeeRates();
        int maxTargetBlocks = 1;
        for(Integer targetBlocks : targetBlocksFeeRates.keySet()) {
            maxTargetBlocks = Math.max(maxTargetBlocks, targetBlocks);
            Double candidate = targetBlocksFeeRates.get(targetBlocks);
            if(feeRate > candidate) {
                return targetBlocks;
            }
        }

        return maxTargetBlocks;
    }

    private void setTargetBlocks(Integer target) {
        targetBlocks.valueProperty().removeListener(targetBlocksListener);
        int index = TARGET_BLOCKS_RANGE.indexOf(target);
        targetBlocks.setValue(index);
        feeRatesChart.select(target);
        targetBlocks.valueProperty().addListener(targetBlocksListener);
    }

    private Map<Integer, Double> getTargetBlocksFeeRates() {
        Map<Integer, Double> retrievedFeeRates = AppController.getTargetBlockFeeRates();
        if(retrievedFeeRates == null) {
            retrievedFeeRates = TARGET_BLOCKS_RANGE.stream().collect(Collectors.toMap(java.util.function.Function.identity(), v -> FALLBACK_FEE_RATE,
                    (u, v) -> { throw new IllegalStateException("Duplicate target blocks"); },
                    LinkedHashMap::new));
        }

        return retrievedFeeRates;
    }

    private Double getFeeRate() {
        return getTargetBlocksFeeRates().get(getTargetBlocks());
    }

    private Double getMinimumFeeRate() {
        Optional<Double> optMinFeeRate = getTargetBlocksFeeRates().values().stream().min(Double::compareTo);
        Double minRate = optMinFeeRate.orElse(FALLBACK_FEE_RATE);
        return Math.max(minRate, Transaction.DUST_RELAY_TX_FEE);
    }

    private boolean isInsufficientFeeRate() {
        return walletTransactionProperty.get() != null && walletTransactionProperty.get().getFeeRate() < AppController.getMinimumRelayFeeRate();
    }

    private void setFeeRate(Double feeRateAmt) {
        feeRate.setText(String.format("%.2f", feeRateAmt) + " sats/vByte");
    }

    private Node getSliderThumb() {
        return targetBlocks.lookup(".thumb");
    }

    public void setMaxInput(ActionEvent event) {
        UtxoSelector utxoSelector = utxoSelectorProperty.get();
        if(utxoSelector == null) {
            MaxUtxoSelector maxUtxoSelector = new MaxUtxoSelector();
            utxoSelectorProperty.set(maxUtxoSelector);
        }

        updateTransaction(true);
    }

    private void setFiatAmount(CurrencyRate currencyRate, Long amount) {
        if(amount != null && currencyRate != null && currencyRate.isAvailable()) {
            fiatAmount.set(currencyRate, amount);
        }
    }

    private void setFiatFeeAmount(CurrencyRate currencyRate, Long amount) {
        if(amount != null && currencyRate != null && currencyRate.isAvailable()) {
            fiatFeeAmount.set(currencyRate, amount);
        }
    }

    private long getRecipientDustThreshold() {
        Address address;
        try {
            address = getRecipientAddress();
        } catch(InvalidAddressException e) {
            address = new P2PKHAddress(getWalletForm().getWallet().getNetwork(), new byte[20]);
        }

        TransactionOutput txOutput = new TransactionOutput(new Transaction(), 1L, address.getOutputScript());
        return address.getScriptType().getDustThreshold(txOutput, getFeeRate());
    }

    private void updateMaxClearButtons(UtxoSelector utxoSelector, UtxoFilter utxoFilter) {
        if(utxoSelector instanceof PresetUtxoSelector) {
            PresetUtxoSelector presetUtxoSelector = (PresetUtxoSelector)utxoSelector;
            int num = presetUtxoSelector.getPresetUtxos().size();
            String selection = " (" + num + " UTXO" + (num != 1 ? "s" : "") + " selected)";
            maxButton.setText("Max" + selection);
            clearButton.setText("Clear" + selection);
        } else if(utxoFilter instanceof ExcludeUtxoFilter) {
            ExcludeUtxoFilter excludeUtxoFilter = (ExcludeUtxoFilter)utxoFilter;
            int num = excludeUtxoFilter.getExcludedUtxos().size();
            String exclusion = " (" + num + " UTXO" + (num != 1 ? "s" : "") + " excluded)";
            maxButton.setText("Max" + exclusion);
            clearButton.setText("Clear" + exclusion);
        } else {
            maxButton.setText("Max");
            clearButton.setText("Clear");
        }
    }

    public void scanQrAddress(ActionEvent event) {
        QRScanDialog qrScanDialog = new QRScanDialog(getWalletForm().getWallet().getNetwork());
        Optional<QRScanDialog.Result> optionalResult = qrScanDialog.showAndWait();
        if(optionalResult.isPresent()) {
            QRScanDialog.Result result = optionalResult.get();
            if(result.uri != null) {
                if(result.uri.getAddress() != null) {
                    address.setText(result.uri.getAddress().toString());
                }
                if(result.uri.getLabel() != null) {
                    label.setText(result.uri.getLabel());
                }
                if(result.uri.getAmount() != null) {
                    setRecipientValueSats(result.uri.getAmount());
                }
            }
        }
    }

    public void clear(ActionEvent event) {
        address.setText("");
        label.setText("");

        amount.textProperty().removeListener(amountListener);
        amount.setText("");
        amount.textProperty().addListener(amountListener);

        fiatAmount.setText("");

        fee.textProperty().removeListener(feeListener);
        fee.setText("");
        fee.textProperty().addListener(feeListener);

        fiatFeeAmount.setText("");

        userFeeSet.set(false);
        targetBlocks.setValue(4);
        utxoSelectorProperty.setValue(null);
        utxoFilterProperty.setValue(null);
        walletTransactionProperty.setValue(null);

        validationSupport.setErrorDecorationEnabled(false);
    }

    private void revalidate(TextField field, ChangeListener<String> listener) {
        field.textProperty().removeListener(listener);
        String amt = field.getText();
        field.setText(amt + "0");
        field.setText(amt);
        field.textProperty().addListener(listener);
    }

    public void createTransaction(ActionEvent event) {
        PSBT psbt = walletTransactionProperty.get().createPSBT();
        EventManager.get().post(new ViewPSBTEvent(label.getText(), psbt));
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            clear(null);
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            if(walletTransactionProperty.get() != null && walletTransactionProperty.get().getSelectedUtxos() != null && allSelectedUtxosSpent(event.getHistoryChangedNodes())) {
                clear(null);
            } else {
                updateTransaction();
            }
        }
    }

    private boolean allSelectedUtxosSpent(List<WalletNode> historyChangedNodes) {
        Set<BlockTransactionHashIndex> unspentUtxos = new HashSet<>(walletTransactionProperty.get().getSelectedUtxos().keySet());

        for(Map.Entry<BlockTransactionHashIndex, WalletNode> selectedUtxoEntry : walletTransactionProperty.get().getSelectedUtxos().entrySet()) {
            BlockTransactionHashIndex utxo = selectedUtxoEntry.getKey();
            WalletNode utxoWalletNode = selectedUtxoEntry.getValue();

            for(WalletNode changedNode : historyChangedNodes) {
                if(utxoWalletNode.equals(changedNode)) {
                    Optional<BlockTransactionHashIndex> spentTxo = changedNode.getTransactionOutputs().stream().filter(txo -> txo.getHash().equals(utxo.getHash()) && txo.getIndex() == utxo.getIndex() && txo.isSpent()).findAny();
                    if(spentTxo.isPresent()) {
                        unspentUtxos.remove(utxo);
                    }
                }
            }
        }

        return unspentUtxos.isEmpty();
    }

    @Subscribe
    public void walletEntryLabelChanged(WalletEntryLabelChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            updateTransaction();
        }
    }

    @Subscribe
    public void feeRatesUpdated(FeeRatesUpdatedEvent event) {
        feeRatesChart.update(event.getTargetBlockFeeRates());
        feeRatesChart.select(getTargetBlocks());
        setFeeRate(event.getTargetBlockFeeRates().get(getTargetBlocks()));
    }

    @Subscribe
    public void spendUtxos(SpendUtxoEvent event) {
        if(!event.getUtxoEntries().isEmpty() && event.getUtxoEntries().get(0).getWallet().equals(getWalletForm().getWallet())) {
            List<BlockTransactionHashIndex> utxos = event.getUtxoEntries().stream().map(HashIndexEntry::getHashIndex).collect(Collectors.toList());
            utxoSelectorProperty.set(new PresetUtxoSelector(utxos));
            utxoFilterProperty.set(null);
            updateTransaction(true);
        }
    }

    @Subscribe
    public void bitcoinUnitChanged(BitcoinUnitChangedEvent event) {
        BitcoinUnit unit = event.getBitcoinUnit();
        if(unit == null || unit.equals(BitcoinUnit.AUTO)) {
            unit = getWalletForm().getWallet().getAutoUnit();
        }
        amountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
        feeAmountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(unit) ? 0 : 1);
    }

    @Subscribe
    public void fiatCurrencySelected(FiatCurrencySelectedEvent event) {
        if(event.getExchangeSource() == ExchangeSource.NONE) {
            fiatAmount.setCurrency(null);
            fiatAmount.setBtcRate(0.0);
        }
    }

    @Subscribe
    public void exchangeRatesUpdated(ExchangeRatesUpdatedEvent event) {
        setFiatAmount(event.getCurrencyRate(), getRecipientValueSats());
        setFiatFeeAmount(event.getCurrencyRate(), getFeeValueSats());
    }

    @Subscribe
    public void excludeUtxo(ExcludeUtxoEvent event) {
        if(event.getWalletTransaction() == walletTransactionProperty.get()) {
            UtxoSelector utxoSelector = utxoSelectorProperty.get();
            if(utxoSelector instanceof MaxUtxoSelector) {
                Collection<BlockTransactionHashIndex> utxos = walletForm.getWallet().getWalletUtxos().keySet();
                utxos.remove(event.getUtxo());
                if(utxoFilterProperty.get() instanceof ExcludeUtxoFilter) {
                    ExcludeUtxoFilter existingUtxoFilter = (ExcludeUtxoFilter)utxoFilterProperty.get();
                    utxos.removeAll(existingUtxoFilter.getExcludedUtxos());
                }
                PresetUtxoSelector presetUtxoSelector = new PresetUtxoSelector(utxos);
                utxoSelectorProperty.set(presetUtxoSelector);
                updateTransaction(true);
            } else if(utxoSelector instanceof PresetUtxoSelector) {
                PresetUtxoSelector presetUtxoSelector = new PresetUtxoSelector(((PresetUtxoSelector)utxoSelector).getPresetUtxos());
                presetUtxoSelector.getPresetUtxos().remove(event.getUtxo());
                utxoSelectorProperty.set(presetUtxoSelector);
                updateTransaction(true);
            } else {
                ExcludeUtxoFilter utxoFilter = new ExcludeUtxoFilter();
                if(utxoFilterProperty.get() instanceof ExcludeUtxoFilter) {
                    ExcludeUtxoFilter existingUtxoFilter = (ExcludeUtxoFilter)utxoFilterProperty.get();
                    utxoFilter.getExcludedUtxos().addAll(existingUtxoFilter.getExcludedUtxos());
                }

                utxoFilter.getExcludedUtxos().add(event.getUtxo());
                utxoFilterProperty.set(utxoFilter);
                updateTransaction();
            }
        }
    }
}
