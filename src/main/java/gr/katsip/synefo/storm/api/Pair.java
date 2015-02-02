package gr.katsip.synefo.storm.api;

import java.io.Serializable;

public class Pair<T1 extends Number, T2 extends Number> implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -5802815705598462469L;

	public T1 lowerBound;
	
	public T2 upperBound;
}
