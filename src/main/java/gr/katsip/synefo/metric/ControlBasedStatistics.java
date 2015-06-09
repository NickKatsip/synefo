package gr.katsip.synefo.metric;

public class ControlBasedStatistics {

	private double slope;
	
	private long timestamp;
	
	public ControlBasedStatistics() {
		slope = 0.0;
		timestamp = -1L;
	}
	
	public void updateSlope(double x) {
		if(timestamp == -1L) {
			timestamp = System.currentTimeMillis();
			slope = 0.0;
		}else {
			long newTimestamp = System.currentTimeMillis();
			long dt = newTimestamp - timestamp;
			slope = (Math.abs(x - slope)) / dt;
		}
	}
	
	public double getSlope() {
		return slope;
	}
	
}
