package gr.katsip.experiment.state.scale;

import gr.katsip.synefo.storm.operators.relational.elastic.joiner.collocated.BasicCollocatedEquiWindow;
import gr.katsip.synefo.storm.operators.relational.elastic.joiner.collocated.CollocatedJoinBolt;
import gr.katsip.synefo.utils.SynefoConstant;

import java.util.LinkedList;

/**
 * Created by katsip on 10/20/2015.
 */
public class SchemaComparison {
    public static void main(String args[]) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(SynefoConstant.COL_SCALE_ACTION_PREFIX + ":" + SynefoConstant.COL_ADD_ACTION);
        stringBuilder.append("|" + SynefoConstant.COL_KEYS + ":");
        stringBuilder.append("|" + SynefoConstant.COL_PEER + ":1");
        System.out.println(CollocatedJoinBolt.isScaleHeader("COL_ACTION:C_ADD|C_KEYS:|C_PEER:11"));
    }
}
