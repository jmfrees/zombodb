//! This Module is to build...
//!
//! https://www.elastic.co/guide/en/elasticsearch/reference/7.9/search-aggregations-pipeline-avg-bucket-aggregation.html
//!
//! Returns JsonB that is a Filer ES Query

use pgx::*;
use serde::*;
use serde_json::*;
use std::collections::HashMap;

#[derive(PostgresEnum, Serialize, Deserialize)]
pub enum GapPolicy {
    Skip,
    InsertZeros,
}

#[pg_extern(immutable, parallel_safe)]
fn avg_pipeline_agg(
    bucket_path: &str,
    gap_policy: Option<default!(GapPolicy, NULL)>,
    format: Option<default!(i64, NULL)>,
) -> JsonB {
    #[derive(Serialize)]
    struct AvgBucket<'a> {
        bucket_path: &'a str,
        #[serde(skip_serializing_if = "Option::is_none")]
        gap_policy: Option<GapPolicy>,
        #[serde(skip_serializing_if = "Option::is_none")]
        format: Option<i64>,
    }
    let bucket = AvgBucket {
        bucket_path,
        gap_policy,
        format,
    };

    JsonB(json! {
       {
         "avg_bucket": bucket
       }
    })
}

#[pg_extern(immutable, parallel_safe)]
fn bucket_script_pipeline_agg(
    script: &str,
    bucket_path_var: Vec<&str>,
    bucket_path_param: Vec<&str>,
    gap_policy: Option<default!(GapPolicy, NULL)>,
    format: Option<default!(i64, NULL)>,
) -> JsonB {
    #[derive(Serialize)]
    struct BucketScript<'a> {
        script: &'a str,
        bucket_path: HashMap<&'a str, &'a str>,
        #[serde(skip_serializing_if = "Option::is_none")]
        gap_policy: Option<GapPolicy>,
        #[serde(skip_serializing_if = "Option::is_none")]
        format: Option<i64>,
    }
    if bucket_path_var.len() != bucket_path_param.len() {
        panic!("Not the same amount of bucket path parts given.")
    }
    let mut bucket_path = HashMap::new();
    for (var, param) in bucket_path_var.iter().zip(bucket_path_param.iter()) {
        bucket_path.insert(*var, *param);
    }
    let bucket_script = BucketScript {
        script,
        bucket_path,
        gap_policy,
        format,
    };

    JsonB(json! {
       {
         "bucket_script": bucket_script
       }
    })
}
