package com.bytezone.dm3270.assistant;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bytezone.dm3270.application.Site;
import com.bytezone.dm3270.display.Screen;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;

public class BatchJobTab extends AbstractTransferTab implements BatchJobListener
{
  private static final Pattern outlistPattern = Pattern
      .compile ("(TSO )?OUT ([A-Z0-9]{2,8})\\((JOB(\\d+))\\) PRINT\\(([A-Z0-9]+)\\)");
  private final BatchJobTable jobTable = new BatchJobTable ();

  private BatchJob selectedBatchJob;

  public BatchJobTab (Screen screen, Site site, TextField text, Button execute)
  {
    super ("Batch Jobs", screen, site, text, execute);

    jobTable.getSelectionModel ().selectedItemProperty ()
        .addListener ( (obs, oldSelection, newSelection) -> select (newSelection));

    setContent (jobTable);
  }

  private void select (BatchJob batchJob)
  {
    if (batchJob == null)
      return;

    selectedBatchJob = batchJob;
    setText ();
    fireJobSelected (batchJob);
  }

  public Optional<BatchJob> getBatchJob (int jobNumber)
  {
    return jobTable.getBatchJob (jobNumber);
  }

  void tsoCommand (String command)
  {
    Matcher matcher = outlistPattern.matcher (command);
    if (matcher.matches ())
    {
      String jobName = matcher.group (2);
      String jobNumber = matcher.group (3);
      String outlist = matcher.group (5) + ".OUTLIST";
      jobTable.setOutlist (jobName, jobNumber, outlist);
    }
  }

  @Override
  protected void setText ()
  {
    if (selectedBatchJob == null || selectedBatchJob.getJobCompleted () == null)
    {
      eraseCommand ();
      return;
    }

    String report = selectedBatchJob.getOutputFile ();
    String tsoPrefix = screenDetails.isTSOCommandScreen () ? "" : "TSO ";
    String ascii = " ASCII CRLF";

    String command = report == null
        ? String.format ("%s%s", tsoPrefix, selectedBatchJob.outputCommand ())
        : String.format ("%sIND$FILE GET %s%s", tsoPrefix, report, ascii);

    txtCommand.setText (command);
    setButton ();
  }

  // ---------------------------------------------------------------------------------//
  // BatchJobListener
  // ---------------------------------------------------------------------------------//

  @Override
  public void batchJobSubmitted (int jobNumber, String jobName)
  {
    jobTable.addBatchJob (new BatchJob (jobNumber, jobName));
  }

  @Override
  public void batchJobEnded (int jobNumber, String jobName, String time,
      int conditionCode)
  {
    Optional<BatchJob> batchJob = getBatchJob (jobNumber);
    if (batchJob.isPresent ())
      batchJob.get ().completed (time, conditionCode);
  }

  @Override
  public void batchJobFailed (int jobNumber, String jobName, String time)
  {
    Optional<BatchJob> batchJob = getBatchJob (jobNumber);
    if (batchJob.isPresent ())
      batchJob.get ().failed (time);
  }

  // ---------------------------------------------------------------------------------//
  // BatchJobSelectionListener
  // ---------------------------------------------------------------------------------//

  private final Set<BatchJobSelectionListener> selectionListeners = new HashSet<> ();

  void fireJobSelected (BatchJob job)
  {
    for (BatchJobSelectionListener listener : selectionListeners)
      listener.jobSelected (job);
  }

  void addJobSelectionListener (BatchJobSelectionListener listener)
  {
    selectionListeners.add (listener);
  }

  void removeJobSelectionListener (BatchJobSelectionListener listener)
  {
    selectionListeners.remove (listener);
  }
}