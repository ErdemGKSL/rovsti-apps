//! Minimal RFC 4180 reader: UTF-8 BOM, quoted fields with `""` escapes,
//! embedded newlines inside quotes, CRLF or LF line ends, blank lines skipped.
//! Field buffers are reused between records, so reading a large file does not
//! allocate per row.

use std::io::{self, BufRead};

/// Longest single record accepted. A stray quote would otherwise swallow the
/// rest of the file into one field.
const MAX_RECORD_BYTES: usize = 1 << 20;

#[derive(Debug)]
pub struct CsvReader<R> {
    r: R,
    header: Vec<String>,
    raw: Vec<u8>,
    fields: Vec<String>,
    count: usize,
    cell: Vec<u8>,
}

impl<R: BufRead> CsvReader<R> {
    /// Reads the header row. An empty input yields an empty header.
    pub fn new(r: R) -> io::Result<Self> {
        let mut me = Self {
            r,
            header: Vec::new(),
            raw: Vec::new(),
            fields: Vec::new(),
            count: 0,
            cell: Vec::new(),
        };
        if me.read_record(true)? {
            me.header = me.fields[..me.count]
                .iter()
                .map(|h| h.trim().to_owned())
                .collect();
        }
        Ok(me)
    }

    /// Index of the header column called `name`.
    pub fn column(&self, name: &str) -> Option<usize> {
        self.header.iter().position(|h| h == name)
    }

    pub fn header(&self) -> &[String] {
        &self.header
    }

    /// The next data record, or `None` at end of input. The slice is valid
    /// until the next call.
    pub fn next_record(&mut self) -> io::Result<Option<&[String]>> {
        if self.read_record(false)? {
            Ok(Some(&self.fields[..self.count]))
        } else {
            Ok(None)
        }
    }

    /// Reads one logical record into `self.fields`; false at end of input.
    fn read_record(&mut self, strip_bom: bool) -> io::Result<bool> {
        loop {
            self.raw.clear();
            let mut quotes = 0usize;
            loop {
                let start = self.raw.len();
                if self.r.read_until(b'\n', &mut self.raw)? == 0 {
                    break;
                }
                quotes += self.raw[start..].iter().filter(|&&b| b == b'"').count();
                // An even quote count means no quoted field is still open.
                if quotes.is_multiple_of(2) {
                    break;
                }
                if self.raw.len() > MAX_RECORD_BYTES {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "csv record too long (unterminated quote?)",
                    ));
                }
            }
            if self.raw.is_empty() {
                return Ok(false);
            }
            let raw = std::mem::take(&mut self.raw);
            let mut body: &[u8] = &raw;
            if strip_bom {
                body = body.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(body);
            }
            while let Some(rest) = body
                .strip_suffix(b"\n")
                .or_else(|| body.strip_suffix(b"\r"))
            {
                body = rest;
            }
            let blank = body.is_empty();
            if !blank {
                self.parse(body);
            }
            self.raw = raw;
            if blank {
                continue;
            }
            return Ok(true);
        }
    }

    fn parse(&mut self, body: &[u8]) {
        self.count = 0;
        self.cell.clear();
        let mut in_quotes = false;
        let mut at_start = true;
        let mut i = 0;
        while i < body.len() {
            let b = body[i];
            if in_quotes {
                if b == b'"' {
                    if body.get(i + 1) == Some(&b'"') {
                        self.cell.push(b'"');
                        i += 1;
                    } else {
                        in_quotes = false;
                    }
                } else {
                    self.cell.push(b);
                }
            } else {
                match b {
                    b'"' if at_start => in_quotes = true,
                    b',' => {
                        self.push_cell();
                        at_start = true;
                        i += 1;
                        continue;
                    }
                    _ => self.cell.push(b),
                }
            }
            at_start = false;
            i += 1;
        }
        self.push_cell();
    }

    fn push_cell(&mut self) {
        if self.count == self.fields.len() {
            self.fields.push(String::new());
        }
        let f = &mut self.fields[self.count];
        f.clear();
        f.push_str(&String::from_utf8_lossy(&self.cell));
        self.cell.clear();
        self.count += 1;
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn rows(input: &[u8]) -> (Vec<String>, Vec<Vec<String>>) {
        let mut r = CsvReader::new(input).unwrap();
        let header = r.header().to_vec();
        let mut out = Vec::new();
        while let Some(rec) = r.next_record().unwrap() {
            out.push(rec.to_vec());
        }
        (header, out)
    }

    #[test]
    fn plain_and_header_lookup() {
        let r = CsvReader::new("a,b,c\n1,2,3\n".as_bytes()).unwrap();
        assert_eq!(r.column("b"), Some(1));
        assert_eq!(r.column("z"), None);
    }

    #[test]
    fn bom_crlf_blank_lines() {
        let (h, rows) = rows(b"\xEF\xBB\xBFid,name\r\n1,A\r\n\r\n2,B");
        assert_eq!(h, ["id", "name"]);
        assert_eq!(rows, [["1", "A"], ["2", "B"]]);
    }

    #[test]
    fn quotes_commas_escapes_and_newlines() {
        let (_, rows) = rows(b"a,b,c\n\"x, y\",\"say \"\"hi\"\"\",\"line1\r\nline2\"\n,,\n");
        assert_eq!(rows[0], ["x, y", "say \"hi\"", "line1\r\nline2"]);
        assert_eq!(rows[1], ["", "", ""]);
    }

    #[test]
    fn utf8_and_empty_input() {
        let (_, rows) = rows("a\nİstanbul Şişli\n".as_bytes());
        assert_eq!(rows[0][0], "İstanbul Şişli");
        let r = CsvReader::new(&b""[..]).unwrap();
        assert!(r.header().is_empty());
    }

    #[test]
    fn unterminated_quote_is_an_error_not_a_hang() {
        let mut data = b"a\n\"".to_vec();
        data.extend(std::iter::repeat_n(b'x', 2 * MAX_RECORD_BYTES));
        data.extend(b"\n".repeat(2));
        let mut r = CsvReader::new(&data[..]).unwrap();
        assert!(r.next_record().is_err());
    }
}
