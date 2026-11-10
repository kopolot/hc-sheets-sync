/**
 * HC Sheets Sync – receiver for the Android app.
 *
 * Bound to a spreadsheet (Extensions -> Apps Script). Deploy as Web app:
 *   Execute as: Me, Who has access: Anyone.
 * Set Script Property SECRET (Project Settings -> Script Properties) to the same value as SYNC_SECRET.
 *
 * POST body: {secret, schema, app_version, days:[{date, kcal_in, ..., workouts:[{id,type,title,start,end,min}]}]}
 * Upserts tab "daily" by date and tab "workouts" by workout id. For every date in the payload,
 * workouts that are no longer present in Health Connect are removed.
 */

var DAILY_SHEET = 'daily';
var WORKOUT_SHEET = 'workouts';

var DAILY_FIELDS = ['date', 'kcal_in', 'protein_g', 'fat_g', 'carbs_g', 'kcal_burned_total', 'kcal_active',
  'steps', 'resting_hr', 'weight_kg', 'body_fat_pct', 'sleep_min'];
var DAILY_HEADERS = DAILY_FIELDS.concat(['workouts_count', 'workouts_min', 'workouts_summary', 'updated_at']);
var WORKOUT_HEADERS = ['id', 'date', 'type', 'title', 'start', 'end', 'min', 'updated_at'];

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    var expected = PropertiesService.getScriptProperties().getProperty('SECRET');
    if (!expected) return json_({ ok: false, error: 'SECRET not set in Script Properties' });
    if (body.secret !== expected) return json_({ ok: false, error: 'bad secret' });
    if (!Array.isArray(body.days)) return json_({ ok: false, error: 'days missing' });

    var lock = LockService.getScriptLock();
    lock.waitLock(30000);
    try {
      var result = write_(SpreadsheetApp.getActive(), body.days);
      return json_({ ok: true, days: result.days, workouts: result.workouts });
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

/** Health check: open the /exec URL in a browser. Returns no data. */
function doGet() {
  return json_({ ok: true, service: 'hc-sheets-sync' });
}

function write_(ss, days) {
  var tz = ss.getSpreadsheetTimeZone();
  var now = new Date();

  // ---- daily ----
  var daily = sheet_(ss, DAILY_SHEET, DAILY_HEADERS, [1]);
  var dailyRows = readRows_(daily, DAILY_HEADERS.length, tz);
  var dailyIndex = indexBy_(dailyRows, 0);

  days.forEach(function (d) {
    var workouts = d.workouts || [];
    var row = DAILY_FIELDS.map(function (f) { return value_(d[f]); }).concat([
      workouts.length,
      workouts.reduce(function (s, w) { return s + (w.min || 0); }, 0),
      workouts.map(function (w) { return w.type + ' ' + w.min + 'min'; }).join('; '),
      now,
    ]);
    upsertRow_(dailyRows, dailyIndex, row, 0);
  });
  dailyRows.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; });
  writeRows_(daily, dailyRows, DAILY_HEADERS.length);

  // ---- workouts ----
  var ws = sheet_(ss, WORKOUT_SHEET, WORKOUT_HEADERS, [1, 2, 5, 6]);
  var wRows = readRows_(ws, WORKOUT_HEADERS.length, tz);
  var sentDates = {};
  var sentIds = {};
  days.forEach(function (d) {
    sentDates[d.date] = true;
    (d.workouts || []).forEach(function (w) { sentIds[w.id] = true; });
  });
  // drop workouts deleted in Health Connect (only for the dates we just sent)
  wRows = wRows.filter(function (r) { return !(sentDates[r[1]] && !sentIds[r[0]]); });
  var wIndex = indexBy_(wRows, 0);
  var workoutCount = 0;
  days.forEach(function (d) {
    (d.workouts || []).forEach(function (w) {
      upsertRow_(wRows, wIndex, [w.id, d.date, w.type, value_(w.title), w.start, w.end, w.min, now], 0);
      workoutCount++;
    });
  });
  wRows.sort(function (a, b) { return a[4] < b[4] ? -1 : a[4] > b[4] ? 1 : 0; });
  writeRows_(ws, wRows, WORKOUT_HEADERS.length);

  return { days: days.length, workouts: workoutCount };
}

/** Gets or creates a sheet with a header row; textCols (1-based) are formatted as plain text. */
function sheet_(ss, name, headers, textCols) {
  var sh = ss.getSheetByName(name) || ss.insertSheet(name);
  sh.getRange(1, 1, 1, headers.length).setValues([headers]).setFontWeight('bold');
  sh.setFrozenRows(1);
  textCols.forEach(function (c) { sh.getRange(1, c, sh.getMaxRows(), 1).setNumberFormat('@'); });
  return sh;
}

function readRows_(sh, width, tz) {
  var last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, width).getValues()
    .filter(function (r) { return r[0] !== '' && r[0] !== null; })
    .map(function (r) {
      return r.map(function (v, i) {
        // tolerate sheets edited by hand where Sheets turned a date string into a Date
        if (v instanceof Date && i < 2) return Utilities.formatDate(v, tz, 'yyyy-MM-dd');
        return v;
      });
    });
}

function writeRows_(sh, rows, width) {
  var last = sh.getLastRow();
  if (last > 1) sh.getRange(2, 1, last - 1, width).clearContent();
  if (rows.length) sh.getRange(2, 1, rows.length, width).setValues(rows);
}

function indexBy_(rows, col) {
  var idx = {};
  rows.forEach(function (r, i) { idx[String(r[col])] = i; });
  return idx;
}

function upsertRow_(rows, idx, row, col) {
  var key = String(row[col]);
  if (idx.hasOwnProperty(key)) rows[idx[key]] = row;
  else { idx[key] = rows.length; rows.push(row); }
}

/** null/undefined -> empty cell, so "no data" is distinguishable from 0. */
function value_(v) {
  return v === null || v === undefined ? '' : v;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
