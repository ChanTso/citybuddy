SELECT CASE
         WHEN DATABASE() = 'cs_db' THEN 'cs_db validated for agent migrations'
         ELSE NULL
       END AS target_validation;
