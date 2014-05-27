
package de.schildbach.wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.IOUtils;
import de.schildbach.wallet.util.Io;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider
{
    public static class ExchangeRate
    {
        public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, final String source)
        {
            this.currencyCode = currencyCode;
            this.rate = rate;
            this.source = source;
        }

        public final String currencyCode;
        public final BigInteger rate;
        public final String source;

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue(rate, Constants.FST_MAX_PRECISION, 0) + ']';
        }
    }

    public static final String KEY_CURRENCY_CODE = "currency_code";
    private static final String KEY_RATE = "rate";
    private static final String KEY_SOURCE = "source";

    private Configuration config;
    private String userAgent;

    @CheckForNull
    private Map<String, ExchangeRate> exchangeRates = null;
    private long lastUpdated = 0;

    private static final URL BITCOINAVERAGE_URL;
    private static final String[] BITCOINAVERAGE_FIELDS = new String[] { "24h_avg", "last" };
    private static final URL BLOCKCHAININFO_URL;
    private static final String[] BLOCKCHAININFO_FIELDS = new String[] { "15m" };

    // https://bitmarket.eu/api/ticker

    static
    {
        try
        {
            BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/ticker/global/all");
            BLOCKCHAININFO_URL = new URL("https://blockchain.info/ticker");
        }
        catch (final MalformedURLException x)
        {
            throw new RuntimeException(x); // cannot happen
        }
    }

    private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;
    private static final int TIMEOUT_MS = 15 * (int) DateUtils.SECOND_IN_MILLIS;

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate()
    {
        final Context context = getContext();

        this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));

        this.userAgent = WalletApplication.httpUserAgent(WalletApplication.packageInfoFromContext(context).versionName);

        final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
        if (cachedExchangeRate != null)
        {
            exchangeRates = new TreeMap<String, ExchangeRate>();
            exchangeRates.put(cachedExchangeRate.currencyCode, cachedExchangeRate);
        }

        return true;
    }

    public static Uri contentUri(@Nonnull final String packageName)
    {
        return Uri.parse("content://" + packageName + '.' + "exchange_rates");
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
    {
        final long now = System.currentTimeMillis();

        Double fst = getCryptsyFSTprice();

        if (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS)
        {
            Map<String, ExchangeRate> newExchangeRates = null;
            if (newExchangeRates == null)
                newExchangeRates = requestExchangeRates(BITCOINAVERAGE_URL, userAgent, BITCOINAVERAGE_FIELDS);
            if (newExchangeRates == null)
                newExchangeRates = requestExchangeRates(BLOCKCHAININFO_URL, userAgent, BLOCKCHAININFO_FIELDS);

            if (newExchangeRates != null)
            {
                exchangeRates = newExchangeRates;
                lastUpdated = now;

                final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
                if (exchangeRateToCache != null)
                    config.setCachedExchangeRate(exchangeRateToCache);
            }
        }

        if (exchangeRates == null)
            return null;

        final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

        if (selection == null)
        {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
            {
                final ExchangeRate rate = entry.getValue();
                cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(fst*rate.rate.longValue()).add(rate.source);
                //cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
            }
        }
        else if (selection.equals(KEY_CURRENCY_CODE))
        {
            final ExchangeRate rate = bestExchangeRate(selectionArgs[0]);
            if (rate != null)
                cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(fst*rate.rate.longValue()).add(rate.source);
                //cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);

        }

        return cursor;
    }

    private ExchangeRate bestExchangeRate(final String currencyCode)
    {
        ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
        if (rate != null)
            return rate;

        final String defaultCode = defaultCurrencyCode();
        rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

        if (rate != null)
            return rate;

        return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String defaultCurrencyCode()
    {
        try
        {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        }
        catch (final IllegalArgumentException x)
        {
            return null;
        }
    }

    public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
    {
        final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
        final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        return new ExchangeRate(currencyCode, rate, source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri)
    {
        throw new UnsupportedOperationException();
    }

    private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String userAgent, final String... fields)
    {
        final long start = System.currentTimeMillis();

        HttpURLConnection connection = null;
        Reader reader = null;

        try
        {
            connection = (HttpURLConnection) url.openConnection();

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.addRequestProperty("User-Agent", userAgent);
            connection.connect();

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK)
            {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
                final StringBuilder content = new StringBuilder();
                Io.copy(reader, content);

                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                final JSONObject head = new JSONObject(content.toString());
                for (final Iterator<String> i = head.keys(); i.hasNext();)
                {
                    final String currencyCode = i.next();
                    if (!"timestamp".equals(currencyCode))
                    {
                        final JSONObject o = head.getJSONObject(currencyCode);

                        for (final String field : fields)
                        {
                            final String rateStr = o.optString(field, null);

                            if (rateStr != null)
                            {
                                try
                                {
                                    final BigInteger rate = GenericUtils.toNanoCoins(rateStr, 0);

                                    if (rate.signum() > 0)
                                    {
                                        rates.put(currencyCode, new ExchangeRate(currencyCode, rate, url.getHost()));
                                        break;
                                    }
                                }
                                catch (final ArithmeticException x)
                                {
                                    log.warn("problem fetching {} exchange rate from {}: {}", new Object[] { currencyCode, url, x.getMessage() });
                                }
                            }
                        }
                    }
                }

                log.info("fetched exchange rates from {}, took {} ms", url, (System.currentTimeMillis() - start));

                return rates;
            }
            else
            {
                log.warn("http status {} when fetching {}", responseCode, url);
            }
        }
        catch (final Exception x)
        {
            log.warn("problem fetching exchange rates from " + url, x);
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (final IOException x)
                {
                    // swallow
                }
            }

            if (connection != null)
                connection.disconnect();
        }

        return null;
    }

    private static Double getCryptsyFSTprice()
    {
        Double d = new Double(0.0);
        try
        {
            //final URL URL = new URL("http://pubapi.cryptsy.com/api.php?method=marketdata");
            final URL URL = new URL("http://pubapi.cryptsy.com/api.php?method=singlemarketdata&marketid=44");
            final HttpURLConnection connection = (HttpURLConnection) URL.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) ;//return null;

            Reader reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
                final StringBuilder content = new StringBuilder();
                IOUtils.copy(reader, content);

                final JSONObject head = new JSONObject(content.toString());
                JSONObject retun = head.getJSONObject("return");
                JSONObject markets = retun.getJSONObject("markets");
                JSONObject fst = markets.getJSONObject("FST");

                d = fst.getDouble("lasttradeprice");

            }
            finally
            {
                if (reader != null)
                    reader.close();
            }
        }
        catch (final Exception x)
        {
            x.printStackTrace();
        }
        return d;

    }

}




    /*

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		final long now = System.currentTimeMillis();

        Double fst = getCryptsyFSTprice();

		if (exchangeRates == null || now - lastUpdated > UPDATE_FREQ_MS)
		{
			Map<String, ExchangeRate> newExchangeRates = getBitcoinCharts();
			if (exchangeRates == null && newExchangeRates == null)
				newExchangeRates = getBlockchainInfo();

			if (newExchangeRates != null)
			{
				exchangeRates = newExchangeRates;
				lastUpdated = now;
			}
		}

		if (exchangeRates == null)
			return null;

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
			{
				final ExchangeRate rate = entry.getValue();
				cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(fst*rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String selectedCode = selectionArgs[0];
			ExchangeRate rate = selectedCode != null ? exchangeRates.get(selectedCode) : null;

			if (rate == null)
			{
				final String defaultCode = defaultCurrencyCode();
				rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

				if (rate == null)
				{
					rate = exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);

					if (rate == null)
						return null;
				}
			}

			cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(fst*rate.rate.longValue()).add(rate.source);
		}

		return cursor;
	}
*/