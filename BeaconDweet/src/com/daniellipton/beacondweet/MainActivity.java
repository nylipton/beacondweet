package com.daniellipton.beacondweet;

import java.io.IOException;
import java.net.URI;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.content.IntentSender;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings.Secure;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.estimote.sdk.Beacon;
import com.estimote.sdk.BeaconManager;
import com.estimote.sdk.BeaconManager.RangingListener;
import com.estimote.sdk.Region;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;

public class MainActivity 
extends Activity 
implements GooglePlayServicesClient.OnConnectionFailedListener, GooglePlayServicesClient.ConnectionCallbacks
{
	private static final Region ALL_BEACONS = new Region("all-beacons", null, null, null ) ;
	private static final String TAG = MainActivity.class.getSimpleName( );
	private BeaconManager beaconManager = null ;
	private String id ;
	private boolean showDevice = false ;
	private static final int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9999 ;
	private LocationClient locationClient ;
	
	@Override
	protected void onStart()
	{
		super.onStart( );
		id = Secure.getString( getContentResolver( ), Secure.ANDROID_ID ) ; // unique id for this device
		TextView deviceTextView = ( TextView ) findViewById( R.id.deviceID ) ;
		deviceTextView.setText( "device id: " + id );
		
		locationClient.connect( ) ; 
		
		beaconManager.connect(
				new BeaconManager.ServiceReadyCallback() {
				    @Override 
				    public void onServiceReady() {
				      try {
				        beaconManager.startRanging(ALL_BEACONS);
				        
				        MainActivity.this.runOnUiThread( new Runnable( ) {
				        	@Override
				        	public void run()
				        	{
				        		
				        		TextView msgText = ( TextView ) MainActivity.this.findViewById( R.id.message ) ;
						        msgText.setText( R.string.title_ranging );
				        	}
				        });
				        
				      } catch (RemoteException e) {
				        Log.e(TAG, "Cannot start ranging", e);
				        MainActivity.this.runOnUiThread( new Runnable( ) {
				        	@Override
				        	public void run()
				        	{
				        		TextView msgText = ( TextView ) MainActivity.this.findViewById( R.id.message ) ;
						        msgText.setText( R.string.title_not_ranging );
						        ProgressBar progressBar = ( ProgressBar ) MainActivity.this.findViewById( R.id.progressBar ) ;
						        progressBar.setVisibility( ProgressBar.GONE );
				        	}
				        });
				      }
				    }
				  });
	}
	
	@Override
	protected void onStop()
	{
		try {
		    beaconManager.stopRanging(ALL_BEACONS);
		  } catch (RemoteException e) {
		    Log.e(TAG, "Cannot stop but it does not matter now", e);
		  }
		
		locationClient.disconnect( ) ;
		super.onStop( );
	}
	
	@Override
	protected void onCreate( Bundle savedInstanceState )
	{
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_main );

		beaconManager = new BeaconManager( this );
		beaconManager.setRangingListener( new Ranger( ) );
		
		locationClient = new LocationClient( this, this, this ) ;
	}
	
	@Override
	protected void onDestroy()
	{
		super.onDestroy( );
		beaconManager.disconnect( );
	}
	
	public void deviceCheckboxClicked( View view )
	{
		boolean checked = ((CheckBox) view).isChecked();
		if( view.getId( ) == R.id.deviceIDCheckbox )
		{
			TextView deviceTextView = ( TextView ) findViewById( R.id.deviceID ) ;
			showDevice = checked ;
			if( checked )
			{
				deviceTextView.setVisibility( TextView.VISIBLE ) ;
			}
			else
			{
				deviceTextView.setVisibility( TextView.GONE ) ;
			}
		}
	}
	
	// used for the names in json
	enum JSONBeaconKeys
	{
		UUID, MAJOR, MINOR, DEVICE, PROXIMITY, LOCATION, LATUTIDE, LONGITUDE ;
	}
	
	class Ranger
	implements RangingListener
	{
		Set<Beacon> knownBeacons = new HashSet<Beacon>( ) ;
		
		@Override
		public void onBeaconsDiscovered( Region region, List<Beacon> beacons )
		{
			Log.d( TAG, "Ranged beacons: " + beacons );
			boolean newBeacon = false ;
			for( Beacon b : beacons )
			{
				if( !knownBeacons.contains( b ) )
				{
					knownBeacons.add( b ) ;
					newBeacon = true ;
					new DweetTask( MainActivity.this ).execute( b ) ; // post this new iBeacon/proximity at dweet.io
				}
			}
			
			if( newBeacon )
			{
				StringBuffer sb = new StringBuffer( ) ;
				for( Beacon b : knownBeacons )
					sb.append( b.getProximityUUID( ) ).append( ", major=" )
					.append( b.getMajor( ) ).append( ", minor=" ).append( b.getMinor( ) )
					.append( '\n' ) ;
	    		TextView msgText = ( TextView ) MainActivity.this.findViewById( R.id.message ) ;
		        msgText.setText( sb.toString( ) );
		        ProgressBar progressBar = ( ProgressBar ) MainActivity.this.findViewById( R.id.progressBar ) ;
		        progressBar.setVisibility( ProgressBar.GONE );
			}

		}
		
		// will post this to dweet.io
		class DweetTask
		extends AsyncTask<Beacon, Void, HttpResponse>
		{
			Context context = null ;
			
			DweetTask( Context context )
			{
				this.context = context ;
			}

			@Override
			protected HttpResponse doInBackground( Beacon... params )
			{
				String addressText = "unknown" ;
				String latitude = "unknown", longitude = "unknown" ;
				if( locationClient != null && locationClient.isConnected( ) )
				{
					List<Address> addresses = null ;
					Geocoder geocoder = new Geocoder( context ) ;
					Location location = locationClient.getLastLocation( ) ;
					NumberFormat nf = NumberFormat.getNumberInstance( ) ;
					
					try {
						addresses = geocoder.getFromLocation( location.getLatitude( ), location.getLongitude( ), 1 ) ;
						// If the reverse geocode returned an address
						if( addresses != null && addresses.size( ) > 0 )
						{
							// Get the first address
							Address address = addresses.get( 0 );
							//Format the first line of address (if available), city, and country name. 
							addressText = String.format( "%s, %s %s, %s",
											// If there's a street address, add it
											address.getMaxAddressLineIndex( ) > 0 ? address.getAddressLine( 0 ) : "",
											// Locality is usually a city
											address.getLocality( ),
											address.getPostalCode( ),
											// The country of the address
											address.getCountryName( ) );
							latitude = nf.format( location.getLatitude( ) ) ;
							longitude = nf.format( location.getLongitude( ) ) ;
						}
					} catch( IOException e ) {
						Log.e( TAG, "Couldn't get address for location", e ) ;
					}
				}
				
				
				HttpResponse response = null;
				Beacon b = params[0] ;
				JSONObject json = new JSONObject( ) ;
				try
				{
					json.put( JSONBeaconKeys.UUID.name( ), b.getProximityUUID( ) ) ;
					json.put( JSONBeaconKeys.MAJOR.name( ), b.getMajor( ) ) ;
					json.put( JSONBeaconKeys.MINOR.name( ), b.getMinor( ) ) ;
//					json.put( JSONBeaconKeys.PROXIMITY.name( ), Utils.computeProximity( b ).toString( ) ) ;
					json.put( JSONBeaconKeys.LOCATION.name( ), addressText ) ;
					json.put( JSONBeaconKeys.LATUTIDE.name( ), latitude ) ;
					json.put( JSONBeaconKeys.LONGITUDE.name( ), longitude ) ;
					if( showDevice ) json.put( JSONBeaconKeys.DEVICE.name( ), id ) ;
					
					try {
						HttpClient client = new DefaultHttpClient( );
						HttpPost request = new HttpPost( );
						StringBuffer uri = new StringBuffer( ) ;
						uri.append(  "https://dweet.io/dweet/for/beacondweet" ) ;
						request.setURI( new URI( uri.toString( ) ) );
						request.setHeader("Content-Type", "application/json" ) ;
						request.setEntity( new StringEntity( json.toString( ) ) );
						response = client.execute( request );
						Log.i( TAG, "http response=" + response.toString( ) ) ;
				    } catch ( Exception e) {
				    	Log.e( TAG, "dweeting error", e ) ;
				    }
					
				} catch( JSONException e )
				{
					Log.e( TAG, "json error?", e ) ;
				}
				return response ;
			}
			
			@Override
			protected void onPostExecute( HttpResponse result )
			{
				// put this in the gui?
			}
		}
	}

	@Override
	public void onConnected( Bundle connectionHint )
	{
		Toast.makeText(this, "Connected for location", Toast.LENGTH_SHORT).show( ) ;
	}

	@Override
	public void onDisconnected()
	{
		Toast.makeText(this, "Disonnected to location services. Please reconnect.", Toast.LENGTH_LONG).show( ) ;
	}

	@Override
	public void onConnectionFailed( ConnectionResult connectionResult )
	{
		/*
         * Google Play services can resolve some errors it detects.
         * If the error has a resolution, try sending an Intent to
         * start a Google Play services activity that can resolve
         * error.
         */
		if( connectionResult.hasResolution( ) )
		{
			try
			{
				// Start an Activity that tries to resolve the error
				connectionResult.startResolutionForResult( this,
						CONNECTION_FAILURE_RESOLUTION_REQUEST );
				/*
				 * Thrown if Google Play services canceled the original
				 * PendingIntent
				 */
			} catch( IntentSender.SendIntentException e )
			{
				// Log the error
				Log.e( TAG, "google play services error", e );
			}
		} else
		{
			/*
			 * If no resolution is available, display a dialog to the user with
			 * the error.
			 */
			Toast.makeText(
					this,
					"Disonnected to location services. Error #"
							+ connectionResult.getErrorCode( ),
					Toast.LENGTH_LONG ).show( );
		}
		
	}

	// @Override
	// public boolean onCreateOptionsMenu( Menu menu )
	// {
	// // Inflate the menu; this adds items to the action bar if it is present.
	// getMenuInflater( ).inflate( R.menu.main, menu );
	// return true;
	// }

}
